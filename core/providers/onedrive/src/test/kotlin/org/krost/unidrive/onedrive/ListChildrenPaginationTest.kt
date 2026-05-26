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

    private fun newServiceWithStore(tokenPath: java.nio.file.Path): GraphApiService =
        GraphApiService(
            config = OneDriveConfig(tokenPath = tokenPath),
            tokenProvider = { _ -> "test-token" },
        )

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

    @Test
    fun `moveItem threads If-Match header when an eTag is supplied`() =
        runTest {
            val driveItemBody = """{"id":"item-mv","name":"renamed.txt","eTag":"\"new-etag,1\""}"""
            var ifMatchSeen: String? = null
            val engine =
                MockEngine { request ->
                    ifMatchSeen = request.headers[HttpHeaders.IfMatch]
                    respond(driveItemBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service = newService()
            installMockClient(service, engine)

            service.moveItem(itemId = "item-mv", newPath = "/renamed.txt", oldPath = "/old.txt", ifMatchETag = """"opaque-etag,7"""")

            assertEquals(""""opaque-etag,7"""", ifMatchSeen, "moveItem must send the caller-supplied eTag in If-Match")
            service.close()
        }

    @Test
    fun `moveItem omits If-Match when no eTag is supplied`() =
        runTest {
            val driveItemBody = """{"id":"item-mv2","name":"renamed.txt"}"""
            var ifMatchSeen: String? = "<not-set>"
            val engine =
                MockEngine { request ->
                    ifMatchSeen = request.headers[HttpHeaders.IfMatch]
                    respond(driveItemBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service = newService()
            installMockClient(service, engine)

            service.moveItem(itemId = "item-mv2", newPath = "/renamed.txt", oldPath = "/old.txt", ifMatchETag = null)

            assertEquals(null, ifMatchSeen, "moveItem must not send If-Match when caller passes null")
            service.close()
        }

    @Test
    fun `moveItem surfaces 412 Precondition Failed as a GraphApiException`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond("eTag mismatch", HttpStatusCode.PreconditionFailed)
                }
            val service = newService()
            installMockClient(service, engine)

            val ex =
                kotlin.test.assertFailsWith<GraphApiException> {
                    service.moveItem(itemId = "item-mv3", newPath = "/r.txt", oldPath = "/o.txt", ifMatchETag = """"stale,1"""")
                }
            assertEquals(412, ex.statusCode, "412 must propagate with its statusCode so the engine can refresh and retry")
            service.close()
        }

    @Test
    fun `uploadSimple URL pins conflictBehavior=replace to match session-upload policy`() =
        runTest {
            val driveItemBody = """{"id":"item-cb","name":"hello.txt","size":3}"""
            var seenUrl: String? = null
            val engine =
                MockEngine { request ->
                    seenUrl = request.url.toString()
                    respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service = newService()
            installMockClient(service, engine)

            service.uploadSimple("/hello.txt", "abc".toByteArray(), fileSystemInfo = null)

            val url = checkNotNull(seenUrl)
            assertTrue(
                url.contains("@microsoft.graph.conflictBehavior=replace"),
                "uploadSimple must send conflictBehavior=replace to match session-upload policy, URL was: $url",
            )
            service.close()
        }

    @Test
    fun `getDelta warns once when the on-disk delta_last_seen is older than the safe window`() =
        runTest {
            val tokenDir = java.nio.file.Files.createTempDirectory("unidrive-delta-safety-old-")
            // Seed delta_last_seen with a timestamp 60 days old — well past the 30-day floor.
            val sixtyDaysAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(60))
            java.nio.file.Files.writeString(tokenDir.resolve("delta_last_seen"), sixtyDaysAgo.toString())

            val deltaBody = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"}"""
            val engine =
                MockEngine { _ ->
                    respond(deltaBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val graphLogger =
                org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.onedrive.GraphApiService")
                    as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            graphLogger.addAppender(appender)
            val previousLevel = graphLogger.level

            try {
                val service = newServiceWithStore(tokenDir)
                installMockClient(service, engine)

                // Two consecutive resume-style calls: the warning must fire exactly once
                // (per-instance latch), not on every page.
                service.getDelta(link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=PRIOR")
                service.getDelta(link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=PRIOR")

                val warns =
                    appender.list.filter {
                        it.level == ch.qos.logback.classic.Level.WARN &&
                            it.formattedMessage.contains("delta cursor was last advanced")
                    }
                assertEquals(1, warns.size, "Cursor-age WARN must fire exactly once per session, was: ${appender.list.map { it.formattedMessage }}")
                assertTrue(warns[0].formattedMessage.contains("60"), "WARN should name the actual age in days, was: ${warns[0].formattedMessage}")
                service.close()
            } finally {
                graphLogger.detachAppender(appender)
                graphLogger.level = previousLevel
                runCatching { java.nio.file.Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
            }
        }

    @Test
    fun `getDelta does not warn when delta_last_seen is recent`() =
        runTest {
            val tokenDir = java.nio.file.Files.createTempDirectory("unidrive-delta-safety-fresh-")
            java.nio.file.Files.writeString(
                tokenDir.resolve("delta_last_seen"),
                java.time.Instant.now().minus(java.time.Duration.ofDays(3)).toString(),
            )

            val deltaBody = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"}"""
            val engine =
                MockEngine { _ ->
                    respond(deltaBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val graphLogger =
                org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.onedrive.GraphApiService")
                    as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            graphLogger.addAppender(appender)

            try {
                val service = newServiceWithStore(tokenDir)
                installMockClient(service, engine)
                service.getDelta(link = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=PRIOR")

                val warns =
                    appender.list.filter {
                        it.level == ch.qos.logback.classic.Level.WARN &&
                            it.formattedMessage.contains("delta cursor was last advanced")
                    }
                assertTrue(warns.isEmpty(), "Recent cursor must not produce a WARN, but got: ${warns.map { it.formattedMessage }}")
                service.close()
            } finally {
                graphLogger.detachAppender(appender)
                runCatching { java.nio.file.Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
            }
        }

    @Test
    fun `resolveUploadSession refreshes stored expiresAt from probe response`() =
        runTest {
            val storeDir = java.nio.file.Files.createTempDirectory("unidrive-store-refresh-")
            val tempFile = java.nio.file.Files.createTempFile("unidrive-refresh-", ".bin")
            java.nio.file.Files.write(tempFile, "y".toByteArray())
            val staleExpiresAt = java.time.Instant.now().plusSeconds(60) // close to expiry
            val freshExpiresAt = java.time.Instant.parse("2030-06-15T12:00:00Z") // server bumped
            val storedUrl = "https://upload.example.com/session/refresh"
            UploadSessionStore(storeDir).put(
                "/refresh.bin",
                storedUrl,
                staleExpiresAt,
                localSize = java.nio.file.Files.size(tempFile),
                localMtimeMillis = java.nio.file.Files.getLastModifiedTime(tempFile).toMillis(),
            )

            val storeFile = storeDir.resolve("upload_sessions.json")
            var storeAfterProbe: String? = null
            val driveItemBody = """{"id":"item-5","name":"refresh.bin","size":1}"""
            val probeBody = """{"uploadUrl":"$storedUrl","expirationDateTime":"$freshExpiresAt","nextExpectedRanges":["0-"]}"""
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url == storedUrl && request.method == HttpMethod.Get ->
                            respond(probeBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        url == storedUrl && request.method == HttpMethod.Put -> {
                            // Snapshot the on-disk store between probe and PUT; the refresh
                            // write must have already happened, while the post-success delete
                            // has not.
                            storeAfterProbe = runCatching { java.nio.file.Files.readString(storeFile) }.getOrNull()
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> error("Unexpected: ${request.method} $url")
                    }
                }
            val service = newServiceWithStore(storeDir)
            installMockClient(service, engine)

            service.uploadLargeFile(localPath = tempFile, remotePath = "/refresh.bin", onProgress = null, fileSystemInfo = null)

            val snapshot = checkNotNull(storeAfterProbe) { "Store file was not observable between probe and PUT" }
            assertTrue(
                snapshot.contains(freshExpiresAt.toString()),
                "Stored expiresAt must have been refreshed to the server's expirationDateTime, snapshot was: $snapshot",
            )
            assertFalse(
                snapshot.contains(staleExpiresAt.toString()),
                "Stale expiresAt must have been overwritten, snapshot was: $snapshot",
            )
            service.close()
            java.nio.file.Files.deleteIfExists(tempFile)
            runCatching { java.nio.file.Files.walk(storeDir).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
        }

    @Test
    fun `uploadLargeFile retries once with a fresh session on chunk-level 410`() =
        runTest {
            val storeDir = java.nio.file.Files.createTempDirectory("unidrive-store-retry-")
            val tempFile = java.nio.file.Files.createTempFile("unidrive-retry-", ".bin")
            java.nio.file.Files.write(tempFile, "z".toByteArray())
            val driveItemBody = """{"id":"item-6","name":"retry.bin","size":1}"""
            val sessionBodyA = """{"uploadUrl":"https://upload.example.com/session/A","expirationDateTime":"2030-01-01T00:00:00Z"}"""
            val sessionBodyB = """{"uploadUrl":"https://upload.example.com/session/B","expirationDateTime":"2030-01-01T00:00:00Z"}"""
            var createCount = 0
            var putA = 0
            var putB = 0
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("createUploadSession") -> {
                            val body = if (createCount++ == 0) sessionBodyA else sessionBodyB
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        url == "https://upload.example.com/session/A" -> {
                            putA++
                            respond("session gone", HttpStatusCode.Gone)
                        }
                        url == "https://upload.example.com/session/B" -> {
                            putB++
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> error("Unexpected URL: $url")
                    }
                }
            val service = newServiceWithStore(storeDir)
            installMockClient(service, engine)

            val result =
                service.uploadLargeFile(localPath = tempFile, remotePath = "/retry.bin", onProgress = null, fileSystemInfo = null)

            assertEquals("item-6", result.id)
            assertEquals(2, createCount, "Expected two createUploadSession calls (initial + retry after 410)")
            assertEquals(1, putA, "First session's PUT must have been attempted once")
            assertEquals(1, putB, "Second session's PUT must have succeeded")
            service.close()
            java.nio.file.Files.deleteIfExists(tempFile)
            runCatching { java.nio.file.Files.walk(storeDir).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
        }

    @Test
    fun `resolveUploadSession tolerates a probe network failure by creating a new session`() =
        runTest {
            val storeDir = java.nio.file.Files.createTempDirectory("unidrive-store-probe-")
            val tempFile = java.nio.file.Files.createTempFile("unidrive-probe-", ".bin")
            java.nio.file.Files.write(tempFile, "p".toByteArray())
            val deadUrl = "https://upload.example.com/session/dead"
            UploadSessionStore(storeDir).put(
                "/probe.bin",
                deadUrl,
                java.time.Instant.now().plusSeconds(3600),
                localSize = java.nio.file.Files.size(tempFile),
                localMtimeMillis = java.nio.file.Files.getLastModifiedTime(tempFile).toMillis(),
            )

            val driveItemBody = """{"id":"item-7","name":"probe.bin","size":1}"""
            val freshSessionBody = """{"uploadUrl":"https://upload.example.com/session/fresh","expirationDateTime":"2030-01-01T00:00:00Z"}"""
            var createCount = 0
            var putFresh = 0
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url == deadUrl -> throw java.io.IOException("connection refused")
                        url.contains("createUploadSession") -> {
                            createCount++
                            respond(freshSessionBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        url == "https://upload.example.com/session/fresh" -> {
                            putFresh++
                            respond(driveItemBody, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        else -> error("Unexpected URL: $url")
                    }
                }
            val service = newServiceWithStore(storeDir)
            installMockClient(service, engine)

            val result =
                service.uploadLargeFile(localPath = tempFile, remotePath = "/probe.bin", onProgress = null, fileSystemInfo = null)

            assertEquals("item-7", result.id)
            assertEquals(1, createCount, "Probe failure should fall through to one createUploadSession call")
            assertEquals(1, putFresh, "Fresh session's PUT must have succeeded")
            service.close()
            java.nio.file.Files.deleteIfExists(tempFile)
            runCatching { java.nio.file.Files.walk(storeDir).sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
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
