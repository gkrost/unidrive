package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.Mirror
import org.krost.unidrive.internxt.model.StartUploadResponse
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtApiServiceTest {
    @Test
    fun `deserialize mirror response`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """[{
            "index": 0,
            "hash": "aabbcc",
            "size": 1024,
            "parity": false,
            "token": "tok123",
            "healthy": true,
            "farmer": {
                "userAgent": "ua",
                "protocol": "https",
                "address": "1.2.3.4",
                "port": 8080,
                "nodeID": "nid",
                "lastSeen": "2026-01-01T00:00:00.000Z"
            },
            "url": "https://1.2.3.4:8080/shards/aabbcc?token=tok123",
            "operation": "PULL"
        }]"""
        val mirrors = json.decodeFromString<List<Mirror>>(raw)
        assertEquals(1, mirrors.size)
        assertEquals("https://1.2.3.4:8080/shards/aabbcc?token=tok123", mirrors[0].url)
        assertEquals("aabbcc", mirrors[0].hash)
    }

    @Test
    fun `deserialize start upload response`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"uploads":[{"index":0,"uuid":"uuid-1","url":"https://storage.example.com/put"}]}"""
        val resp = json.decodeFromString<StartUploadResponse>(raw)
        assertEquals(1, resp.uploads.size)
        assertEquals("uuid-1", resp.uploads[0].uuid)
        assertEquals("https://storage.example.com/put", resp.uploads[0].url)
    }

    @Test
    fun `delta cursor rewinds 120 seconds (drive-desktop parity)`() {
        // Was 6 hours pre-2026-05-20; tightened to 2 minutes after the
        // cursor-promotion-always fix made the 6h defensive window obsolete.
        // Matches drive-desktop's `TWO_MINUTES_IN_MILLISECONDS`. On a hot
        // account this is the difference between a sub-second incremental
        // walk and a multi-minute one.
        val cursor = "2026-03-29T18:30:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-29T18:28:00Z", rewound)
    }

    @Test
    fun `delta cursor rewind crosses midnight (still only 120 seconds back)`() {
        val cursor = "2026-03-29T00:01:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-28T23:59:00Z", rewound)
    }

    @Test
    fun `UD-369 stripExtension removes the last dot-separated segment`() {
        assertEquals("report", InternxtProvider.stripExtension("report.docx"))
        assertEquals("archive.tar", InternxtProvider.stripExtension("archive.tar.gz"))
        assertEquals("noext", InternxtProvider.stripExtension("noext"))
        assertEquals("", InternxtProvider.stripExtension(".dotfile"))
    }

    @Test
    fun `UD-369 newFileType returns the bare extension or null`() {
        assertEquals("docx", InternxtProvider.newFileType("report.docx"))
        assertEquals("gz", InternxtProvider.newFileType("archive.tar.gz"))
        assertEquals(null, InternxtProvider.newFileType("noext"))
        assertEquals("dotfile", InternxtProvider.newFileType(".dotfile"))
    }

    @Test
    fun `split path into segments`() {
        val segments = InternxtProvider.pathSegments("/Documents/Work/report.pdf")
        assertEquals(listOf("Documents", "Work", "report.pdf"), segments)
    }

    @Test
    fun `split root path returns empty`() {
        val segments = InternxtProvider.pathSegments("/")
        assertEquals(emptyList(), segments)
    }

    @Test
    fun `bridge auth header is correctly formed`() {
        val user = "user@example.com"
        val userId = "secret"
        // sha256("secret") = 2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b
        val header = InternxtCrypto.bridgeAuthHeader(user, userId)
        val expected =
            Base64.getEncoder().encodeToString(
                "$user:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b".toByteArray(),
            )
        assertEquals("Basic $expected", header)
    }

    // UD-335: tactical retry helpers (parseRetryAfter + retryOnTransient).

    private fun mkService(): InternxtApiService =
        InternxtApiService(
            InternxtConfig(),
            credentialsProvider = { _ ->
                // Tests don't actually call any HTTP method that needs creds —
                // they exercise the retry/parse helpers directly. Surface a
                // sentinel so any accidental real call fails loudly.
                error("test fixture: credentialsProvider must not be called")
            },
        )

    @Test
    fun `UD-335 parseRetryAfter pulls seconds from Cloudflare-style body and returns ms`() {
        val service = mkService()
        val body =
            """API error: 502 Bad Gateway - {"type":"...","retryable":true,"retry_after":60,"detail":"..."}"""
        assertEquals(60_000L, service.parseRetryAfter(body))
    }

    @Test
    fun `UD-335 parseRetryAfter returns null when field absent`() {
        val service = mkService()
        assertEquals(null, service.parseRetryAfter("API error: 502 - {}"))
        assertEquals(null, service.parseRetryAfter(null))
    }

    @Test
    fun `UD-335 retryOnTransient succeeds on second attempt after 502`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            var attempts = 0
            val result =
                service.retryOnTransient {
                    attempts++
                    if (attempts < 2) throw InternxtApiException("API error: 502 - {\"retry_after\":1}", 502)
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(2, attempts)
        }

    @Test
    fun `UD-335 retryOnTransient does not retry non-transient status`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            var attempts = 0
            try {
                service.retryOnTransient {
                    attempts++
                    throw InternxtApiException("API error: 400 - bad request", 400)
                }
                kotlin.test.fail("expected InternxtApiException")
            } catch (e: InternxtApiException) {
                assertEquals(400, e.statusCode)
            }
            assertEquals(1, attempts)
        }

    @Test
    fun `UD-372 InternxtFile eagerly parses creation+modification timestamps via JSON deserialise`() {
        val raw =
            """{"uuid":"u1","creationTime":"2026-05-03T17:39:55.123Z","modificationTime":"2026-05-03T18:00:00Z"}"""
        val file = Json { ignoreUnknownKeys = true }.decodeFromString<org.krost.unidrive.internxt.model.InternxtFile>(raw)
        assertEquals(java.time.Instant.parse("2026-05-03T17:39:55.123Z"), file.creationInstant)
        assertEquals(java.time.Instant.parse("2026-05-03T18:00:00Z"), file.modificationInstant)
        // Wire fields preserved.
        assertEquals("2026-05-03T17:39:55.123Z", file.creationTime)
        assertEquals("2026-05-03T18:00:00Z", file.modificationTime)
    }

    @Test
    fun `UD-372 InternxtFile timestamps null when wire field absent`() {
        val raw = """{"uuid":"u1"}"""
        val file = Json { ignoreUnknownKeys = true }.decodeFromString<org.krost.unidrive.internxt.model.InternxtFile>(raw)
        assertEquals(null, file.creationInstant)
        assertEquals(null, file.modificationInstant)
    }

    @Test
    fun `UD-372 InternxtFile timestamps null when wire field is malformed (lenient parse)`() {
        val raw = """{"uuid":"u1","creationTime":"not-a-date","modificationTime":""}"""
        val file = Json { ignoreUnknownKeys = true }.decodeFromString<org.krost.unidrive.internxt.model.InternxtFile>(raw)
        assertEquals(null, file.creationInstant)
        assertEquals(null, file.modificationInstant)
    }

    @Test
    fun `UD-372 InternxtFolder eagerly parses timestamps via JSON deserialise`() {
        val raw =
            """{"uuid":"f1","creationTime":"2026-05-03T17:39:55Z","modificationTime":"2026-05-03T18:00:00Z"}"""
        val folder = Json { ignoreUnknownKeys = true }.decodeFromString<org.krost.unidrive.internxt.model.InternxtFolder>(raw)
        assertEquals(java.time.Instant.parse("2026-05-03T17:39:55Z"), folder.creationInstant)
        assertEquals(java.time.Instant.parse("2026-05-03T18:00:00Z"), folder.modificationInstant)
    }

    @Test
    fun `UD-368 createFoldersBatch rejects empty list`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            try {
                service.createFoldersBatch("parent-uuid", emptyList())
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                kotlin.test.assertTrue(e.message!!.contains("at least one item"), "actual: ${e.message}")
            }
        }

    @Test
    fun `UD-368 createFoldersBatch rejects more than 5 items (server cap)`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            val tooMany = (1..6).map { "name-$it" to "enc-$it" }
            try {
                service.createFoldersBatch("parent-uuid", tooMany)
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                kotlin.test.assertTrue(e.message!!.contains("server-capped at 5"), "actual: ${e.message}")
            }
        }

    @Test
    fun `UD-367 trashItems rejects empty list`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            try {
                service.trashItems(emptyList())
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                kotlin.test.assertTrue(e.message!!.contains("at least one item"), "actual: ${e.message}")
            }
        }

    @Test
    fun `UD-367 trashItems rejects more than 50 items (server cap)`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            val tooMany = (1..51).map { "uuid-$it" to "file" }
            try {
                service.trashItems(tooMany)
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                kotlin.test.assertTrue(e.message!!.contains("server-capped at 50"), "actual: ${e.message}")
            }
        }

    @Test
    fun `UD-367 trashItems rejects invalid type values`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            try {
                service.trashItems(listOf("uuid-1" to "directory"))
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                kotlin.test.assertTrue(e.message!!.contains("must be 'file' or 'folder'"), "actual: ${e.message}")
            }
        }

    @Test
    fun `UD-335 retryOnTransient surfaces original exception after budget exhausted`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            var attempts = 0
            try {
                service.retryOnTransient(maxAttempts = 3) {
                    attempts++
                    throw InternxtApiException("API error: 502 - {}", 502)
                }
                kotlin.test.fail("expected InternxtApiException")
            } catch (e: InternxtApiException) {
                assertEquals(502, e.statusCode)
            }
            assertEquals(3, attempts)
        }

    // UD-353: OVH PUT timeout floor — documents the regression that the
    // default UploadTimeoutPolicy.computeRequestTimeoutMs floor (50 KiB/s)
    // produced for the 2026-04-30 incident, and the fix shape (10 KiB/s
    // OVH-specific override) without exposing the private constant.

    @Test
    fun `UD-353 default 50 KiB-s floor pins a 10 MiB file to 600s — the regression`() {
        // Pre-fix behaviour: 10 MiB / 50 KiB/s = 204 s → floor (600 s) wins.
        // The 2026-04-30 incident's failed clips fell in the 5–30 MiB band
        // exactly here. OVH observed at ~30 KB/s during congestion makes a
        // 10 MiB upload legitimately need ~340 s; with TCP slow-start +
        // connection setup it routinely exceeds 600 s and the client tears
        // the still-progressing connection down. This test pins that bug.
        val sizeBytes = 10L * 1024 * 1024
        val timeoutMs =
            org.krost.unidrive.http.UploadTimeoutPolicy
                .computeRequestTimeoutMs(sizeBytes)
        assertEquals(600_000L, timeoutMs)
    }

    @Test
    fun `UD-353 OVH-pessimistic 10 KiB-s floor grants a 10 MiB file 17 minutes`() {
        // Post-fix behaviour: same 10 MiB shard with the OVH-specific
        // 10 KiB/s override returns 1024 s ≈ 17 min — easily covers the
        // 1000 s legitimate-progress upper bound. The 60 s
        // socketTimeoutMillis watchdog still catches stalled connections,
        // so slow-loris exposure is unchanged; we only grant more
        // wall-clock for actual byte-flowing uploads against OVH's slow
        // third-party endpoint.
        val sizeBytes = 10L * 1024 * 1024
        val ovhMinThroughputBps = 10L * 1024 // mirrors InternxtApiService.OVH_PUT_MIN_THROUGHPUT_BPS
        val timeoutMs =
            org.krost.unidrive.http.UploadTimeoutPolicy.computeRequestTimeoutMs(
                fileSize = sizeBytes,
                minThroughputBytesPerSecond = ovhMinThroughputBps,
            )
        assertEquals(1024_000L, timeoutMs)
    }

    @Test
    fun `UD-358 listing query params default to sort=uuid and order=ASC`() {
        // The default (no sort arg) is the offset-stable uuid sort used by
        // the fresh-cursor full-enum path. Tombstones default to ALL so
        // callers must opt out explicitly when they want EXISTS-only.
        val params = InternxtApiService.listingQueryParams(updatedAt = null, limit = 50, offset = 0)
        assertEquals("uuid", params["sort"])
        assertEquals("ASC", params["order"])
        assertEquals("ALL", params["status"], "tombstones default-visible; delta-path opts EXISTS only on fresh scans")
        assertEquals("50", params["limit"])
        assertEquals("0", params["offset"])
        kotlin.test.assertNull(params["updatedAt"], "no updatedAt when null")
    }

    @Test
    fun `UD-358 listing query params pass through updatedAt cursor`() {
        val cursor = "2026-03-29T12:00:00Z"
        val params = InternxtApiService.listingQueryParams(updatedAt = cursor, limit = 100, offset = 50)
        assertEquals(cursor, params["updatedAt"])
        assertEquals("uuid", params["sort"])
        assertEquals("ASC", params["order"])
        assertEquals("100", params["limit"])
        assertEquals("50", params["offset"])
    }

    @Test
    fun `listing query params honour explicit sort and status overrides for the delta path`() {
        // drive-desktop pattern: incremental delta walks pass sort=updatedAt
        // (so the per-page max() advances the effective cursor) and
        // status=ALL (tombstones are the signal for "deleted since cursor").
        // Verified against drive-desktop sync-remote-files.ts:30 + swagger
        // schema.ts:5115/5119. Fresh full-enum walks instead pass
        // status=EXISTS to skip the ~20–40 % tombstone payload on a
        // long-lived account.
        val deltaParams =
            InternxtApiService.listingQueryParams(
                updatedAt = "2026-05-20T08:00:00Z",
                limit = 999,
                offset = 0,
                status = "ALL",
                sort = "updatedAt",
            )
        assertEquals("updatedAt", deltaParams["sort"])
        assertEquals("ALL", deltaParams["status"])

        val freshParams =
            InternxtApiService.listingQueryParams(
                updatedAt = null,
                limit = 999,
                offset = 0,
                status = "EXISTS",
                sort = "uuid",
            )
        assertEquals("uuid", freshParams["sort"])
        assertEquals("EXISTS", freshParams["status"])
    }

    // UD-807: rewritten to use kotlinx-coroutines-test `runTest` + virtual time.
    // The previous wall-clock variant spawned 20 `Dispatchers.Default` coroutines
    // and spin-waited with `delay(10)` for 500 ms; that budget was too tight on
    // slow Windows CI runners, where the 20 callers occasionally hadn't all
    // installed themselves before the gate was released. The dedup invariant
    // (one loader invocation across N concurrent load() calls for the same key)
    // is timing-independent — InFlightDedup is pure concurrency coordination
    // (ConcurrentHashMap + async + Deferred). Running on the test scheduler
    // lets us use `yield()` to deterministically suspend all callers on the
    // gate before any of them is released, removing the race window without
    // changing the production code path.
    @Test
    fun `UD-205 folderContents dedup collapses concurrent loads for the same uuid into one`() =
        kotlinx.coroutines.test.runTest {
            val service = mkService()
            val loaderInvocations = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            val payload = FolderContentResponse()

            // Launch 20 concurrent callers on the test scheduler. They all
            // suspend inside the loader on `gate.await()` (after the winner
            // increments `loaderInvocations`) or inside `winner.await()` (for
            // losers awaiting the shared deferred). `yield()` runs all
            // currently-queued continuations until every caller is parked.
            val deferreds =
                (1..20).map {
                    async {
                        service.folderContentsDedup.load("folder-uuid-A") {
                            loaderInvocations.incrementAndGet()
                            gate.await()
                            payload
                        }
                    }
                }

            // Drain the scheduler until all 20 callers are suspended:
            //  - the winner is parked on `gate.await()` after bumping the
            //    counter once;
            //  - the 19 losers are parked on `winner.await()` in InFlightDedup.
            // testScheduler.advanceUntilIdle() runs every queued continuation
            // that has no outstanding wall-clock dependency.
            testScheduler.advanceUntilIdle()
            assertEquals(
                1,
                loaderInvocations.get(),
                "exactly one caller should have entered the loader before the gate opens",
            )

            // Release the loader. All 20 callers now resolve to the same payload.
            gate.complete(Unit)
            val results = deferreds.awaitAll()

            assertEquals(20, results.size)
            kotlin.test.assertTrue(results.all { it === payload })
            assertEquals(
                1,
                loaderInvocations.get(),
                "concurrent getFolderContents calls for same UUID must share one underlying load",
            )

            // Different UUID → fresh loader invocation (no cross-key interference).
            service.folderContentsDedup.load("folder-uuid-B") {
                loaderInvocations.incrementAndGet()
                payload
            }
            assertEquals(2, loaderInvocations.get())
        }

    @Test
    fun `driveBudget records a throttle when a wrapped call surfaces 503`() =
        kotlinx.coroutines.test.runTest {
            // Wire a budget with an injectable clock; after a 503 surfaces from
            // createFolder (Drive REST surface), the budget must observe
            // currentSpacingMs() > 0 i.e. it transitioned out of the steady-state
            // no-throttle fast path. This pins the per-attempt
            // budget.recordThrottle hook on the Drive REST path.
            var virtualMs = 1_000L
            val budget =
                org.krost.unidrive.http.HttpRetryBudget(
                    maxConcurrency = 2,
                    minSpacingMs = 500,
                    stormSpacingMs = 1_000,
                    clock = { virtualMs },
                )
            val service =
                InternxtApiService(
                    InternxtConfig(),
                    { _ ->
                        org.krost.unidrive.internxt.model.InternxtCredentials(
                            jwt = "test-jwt",
                            mnemonic = "test-mnemonic",
                            rootFolderId = "test-root",
                            email = "test@example.invalid",
                        )
                    },
                    driveBudget = budget,
                )
            // Swap the httpClient with a MockEngine that always returns 503.
            val engine =
                io.ktor.client.engine.mock.MockEngine {
                    respond(
                        content = """{"detail":"service unavailable"}""",
                        status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                    )
                }
            val field = InternxtApiService::class.java.getDeclaredField("httpClient")
            field.isAccessible = true
            (field.get(service) as? io.ktor.client.HttpClient)?.close()
            field.set(service, io.ktor.client.HttpClient(engine))

            assertEquals(0L, budget.currentSpacingMs(), "pre-call: steady-state fast path")
            try {
                service.createFolder("parent-uuid", "name", "encrypted-name")
                kotlin.test.fail("expected InternxtApiException 503")
            } catch (e: InternxtApiException) {
                assertEquals(503, e.statusCode)
            }
            kotlin.test.assertTrue(
                budget.currentSpacingMs() > 0L,
                "post-503: budget should observe the throttle and exit the fast path",
            )
            service.close()
        }

    @Test
    fun `UD-353 sub-floor files still get the 600s floor under the OVH override`() {
        // A 2 MiB shard at 10 KiB/s = 205 s, below the 600 s floor. Floor
        // wins — sub-floor files are unchanged from the default behaviour.
        val sizeBytes = 2L * 1024 * 1024
        val ovhMinThroughputBps = 10L * 1024
        val timeoutMs =
            org.krost.unidrive.http.UploadTimeoutPolicy.computeRequestTimeoutMs(
                fileSize = sizeBytes,
                minThroughputBytesPerSecond = ovhMinThroughputBps,
            )
        assertEquals(600_000L, timeoutMs)
    }

    /**
     * 401 → automatic refresh-and-replay contract: a Drive REST call that
     * returns 401 once must replay exactly once after a forced refresh and
     * succeed on the second attempt. Asserts:
     *  - exactly two HTTP requests fire (original + post-refresh replay),
     *    not three (no second refresh on the 200 response),
     *  - the credentialsProvider is invoked twice: once with
     *    `forceRefresh = false` (pre-call), once with `forceRefresh = true`
     *    (post-401 forced refresh).
     *
     * Pairs with `InternxtAuthServiceTest`'s
     * "forceRefresh refreshes even when stored jwt is still fresh" — that
     * test pins the AuthService side of the seam; this pins the
     * InternxtApiService side. A regression on either drops the
     * mid-session-401-recovery guarantee.
     */
    // Internxt finishUpload idempotency: 409 MissingUploadsError detection +
    // folder-listing reconcile. These tests pin the helper's behaviour at the
    // API-service layer with stateful MockEngines keyed on URL path.

    private fun newServiceWithMock(
        engine: io.ktor.client.engine.mock.MockEngine,
    ): InternxtApiService {
        val service =
            InternxtApiService(
                InternxtConfig(),
                { _ ->
                    org.krost.unidrive.internxt.model.InternxtCredentials(
                        jwt = "test-jwt",
                        mnemonic = "test-mnemonic",
                        rootFolderId = "test-root",
                        email = "test@example.invalid",
                        bridgeUser = "bridge-user",
                        bridgeUserId = "bridge-secret",
                        bucket = "test-bucket",
                    )
                },
            )
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? io.ktor.client.HttpClient)?.close()
        field.set(service, io.ktor.client.HttpClient(engine))
        return service
    }

    private val sampleBucketEntryJson =
        """{"id":"bridge-fileId-1","index":"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff","bucket":"test-bucket","name":"enc-name"}"""

    private val missingUploadsBodyShape =
        """{"statusCode":409,"message":"MissingUploadsError: shard upload missing for finish","error":"Conflict"}"""

    @Test
    fun `commitWithRetry succeeds on first attempt without reconcile`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val listingCalls = AtomicInteger(0)
            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = sampleBucketEntryJson,
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/") -> {
                            listingCalls.incrementAndGet()
                            respond(
                                content = """{"children":[],"files":[]}""",
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
                val result =
                    commitWithRetry(
                        api = service,
                        bucket = "test-bucket",
                        folderUuid = "folder-uuid-1",
                        plainName = "report",
                        ext = "pdf",
                        fileSize = 1024L,
                        encryptedSize = 1040L,
                        indexHex = "00".repeat(32),
                        hashHex = "aa".repeat(32),
                        shardUuid = "shard-uuid-1",
                        startedAt = startedAt,
                        clock = { startedAt.plusMillis(100) },
                    )
                assertEquals("bridge-fileId-1", result.id)
                assertEquals(1, finishCalls.get(), "exactly one finishUpload call on the happy path")
                assertEquals(0, listingCalls.get(), "no reconcile listing when finish succeeds")
            } finally {
                service.close()
            }
        }

    @Test
    fun `commitWithRetry on 409 MissingUploadsError reconciles via folder listing and returns existing fileId`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val listingCalls = AtomicInteger(0)
            val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
            val creationTime = startedAt.plusSeconds(2).toString()
            val listingBody =
                """{"children":[],"files":[{
                    "uuid":"file-uuid-1",
                    "fileId":"bridge-fileId-2",
                    "plainName":"report",
                    "type":"pdf",
                    "size":"1024",
                    "bucket":"test-bucket",
                    "status":"EXISTS",
                    "creationTime":"$creationTime"
                }]}"""
            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = missingUploadsBodyShape,
                                status = io.ktor.http.HttpStatusCode.Conflict,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/folder-uuid-1") -> {
                            listingCalls.incrementAndGet()
                            respond(
                                content = listingBody,
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val result =
                    commitWithRetry(
                        api = service,
                        bucket = "test-bucket",
                        folderUuid = "folder-uuid-1",
                        plainName = "report",
                        ext = "pdf",
                        fileSize = 1024L,
                        encryptedSize = 1040L,
                        indexHex = "00".repeat(32),
                        hashHex = "aa".repeat(32),
                        shardUuid = "shard-uuid-1",
                        startedAt = startedAt,
                        clock = { startedAt.plusMillis(500) },
                    )
                assertEquals("bridge-fileId-2", result.id, "reconcile returns the listing's fileId")
                assertEquals(1, finishCalls.get(), "first 409 only — no re-attempt when reconcile succeeds")
                assertEquals(1, listingCalls.get(), "exactly one reconcile listing call")
            } finally {
                service.close()
            }
        }

    @Test
    fun `commitWithRetry on 409 with no matching name re-attempts finishUpload once then throws`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val listingCalls = AtomicInteger(0)
            val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = missingUploadsBodyShape,
                                status = io.ktor.http.HttpStatusCode.Conflict,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/") -> {
                            listingCalls.incrementAndGet()
                            respond(
                                content = """{"children":[],"files":[]}""",
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val ex =
                    kotlin.test.assertFailsWith<InternxtApiException> {
                        commitWithRetry(
                            api = service,
                            bucket = "test-bucket",
                            folderUuid = "folder-uuid-1",
                            plainName = "report",
                            ext = "pdf",
                            fileSize = 1024L,
                            encryptedSize = 1040L,
                            indexHex = "00".repeat(32),
                            hashHex = "aa".repeat(32),
                            shardUuid = "shard-uuid-1",
                            startedAt = startedAt,
                            clock = { startedAt.plusMillis(500) },
                        )
                    }
                assertEquals(409, ex.statusCode)
                kotlin.test.assertTrue(
                    ex.message?.contains("MissingUploadsError") == true,
                    "thrown message must reference the marker substring, got: ${ex.message}",
                )
                kotlin.test.assertNotNull(ex.cause, "cause must be the original 409 exception")
                kotlin.test.assertTrue(
                    (ex.cause as? InternxtApiException)?.statusCode == 409,
                    "cause must carry the original 409 statusCode",
                )
                assertEquals(2, finishCalls.get(), "case (a): one initial 409 + one re-attempt that also 409s")
                assertEquals(1, listingCalls.get(), "one reconcile listing before the re-attempt")
            } finally {
                service.close()
            }
        }

    @Test
    fun `commitWithRetry on 409 with multiple size-matching candidates picks the youngest`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
            val olderCreation = startedAt.plusSeconds(60).toString() // 1 minute after start
            val youngerCreation = startedAt.plusSeconds(240).toString() // 4 minutes after start
            val listingBody =
                """{"children":[],"files":[
                    {"uuid":"file-uuid-old","fileId":"bridge-fileId-OLD","plainName":"report","type":"pdf","size":"1024","bucket":"test-bucket","status":"EXISTS","creationTime":"$olderCreation"},
                    {"uuid":"file-uuid-new","fileId":"bridge-fileId-NEW","plainName":"report","type":"pdf","size":"1024","bucket":"test-bucket","status":"EXISTS","creationTime":"$youngerCreation"}
                ]}"""

            // Attach a list appender to capture the WARN log emitted on disambiguation.
            val disambiguationLogger =
                org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.internxt.FinishUploadIdempotency")
                    as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            disambiguationLogger.addAppender(appender)

            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = missingUploadsBodyShape,
                                status = io.ktor.http.HttpStatusCode.Conflict,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/") -> {
                            respond(
                                content = listingBody,
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val result =
                    commitWithRetry(
                        api = service,
                        bucket = "test-bucket",
                        folderUuid = "folder-uuid-1",
                        plainName = "report",
                        ext = "pdf",
                        fileSize = 1024L,
                        encryptedSize = 1040L,
                        indexHex = "00".repeat(32),
                        hashHex = "aa".repeat(32),
                        shardUuid = "shard-uuid-1",
                        startedAt = startedAt,
                        clock = { startedAt.plusMillis(500) },
                    )
                assertEquals("bridge-fileId-NEW", result.id, "youngest creationTime wins the disambiguation")
                assertEquals(1, finishCalls.get(), "first 409 only — no re-attempt when reconcile succeeds")
                val warn =
                    appender.list.firstOrNull {
                        it.level == ch.qos.logback.classic.Level.WARN &&
                            it.formattedMessage.contains("reconcile matched")
                    }
                kotlin.test.assertNotNull(warn, "expected disambiguation WARN, got: ${appender.list.map { it.formattedMessage }}")
                kotlin.test.assertTrue(
                    warn.formattedMessage.contains("bridge-fileId-NEW"),
                    "WARN must name the chosen youngest fileId, got: ${warn.formattedMessage}",
                )
                kotlin.test.assertTrue(
                    warn.formattedMessage.contains("bridge-fileId-OLD"),
                    "WARN must list the rejected sibling fileId, got: ${warn.formattedMessage}",
                )
            } finally {
                disambiguationLogger.detachAppender(appender)
                appender.stop()
                service.close()
            }
        }

    @Test
    fun `commitWithRetry reconcile listing failure wraps the listing error as cause`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val listingCalls = AtomicInteger(0)
            val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = missingUploadsBodyShape,
                                status = io.ktor.http.HttpStatusCode.Conflict,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/") -> {
                            listingCalls.incrementAndGet()
                            // Internal Server Error — a transient that bubbles up before retry exhaustion.
                            respond(
                                content = """{"error":"internal server error"}""",
                                status = io.ktor.http.HttpStatusCode.InternalServerError,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val ex =
                    kotlin.test.assertFailsWith<InternxtApiException> {
                        commitWithRetry(
                            api = service,
                            bucket = "test-bucket",
                            folderUuid = "folder-uuid-1",
                            plainName = "report",
                            ext = "pdf",
                            fileSize = 1024L,
                            encryptedSize = 1040L,
                            indexHex = "00".repeat(32),
                            hashHex = "aa".repeat(32),
                            shardUuid = "shard-uuid-1",
                            startedAt = startedAt,
                            clock = { startedAt.plusMillis(500) },
                        )
                    }
                assertEquals(409, ex.statusCode, "wrapped exception keeps the 409 statusCode")
                kotlin.test.assertTrue(
                    ex.message?.contains("MissingUploadsError") == true,
                    "wrapped exception preserves the marker substring, got: ${ex.message}",
                )
                kotlin.test.assertNotNull(ex.cause, "cause must be the listing exception")
                kotlin.test.assertTrue(
                    (ex.cause as? InternxtApiException)?.statusCode == 500,
                    "cause must carry the listing's statusCode (500), got: ${(ex.cause as? InternxtApiException)?.statusCode}",
                )
                assertEquals(1, finishCalls.get(), "no re-attempt when listing fails")
            } finally {
                service.close()
            }
        }

    @Test
    fun `commitWithRetry propagates non-MissingUploads 409 without reconcile`() =
        kotlinx.coroutines.test.runTest {
            val finishCalls = AtomicInteger(0)
            val listingCalls = AtomicInteger(0)
            val startedAt = java.time.Instant.parse("2026-05-18T10:00:00Z")
            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                            finishCalls.incrementAndGet()
                            respond(
                                content = """{"statusCode":409,"message":"Some other conflict","error":"Conflict"}""",
                                status = io.ktor.http.HttpStatusCode.Conflict,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url.contains("/folders/content/") -> {
                            listingCalls.incrementAndGet()
                            respond(
                                content = """{"children":[],"files":[]}""",
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL: $url")
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                val ex =
                    kotlin.test.assertFailsWith<InternxtApiException> {
                        commitWithRetry(
                            api = service,
                            bucket = "test-bucket",
                            folderUuid = "folder-uuid-1",
                            plainName = "report",
                            ext = "pdf",
                            fileSize = 1024L,
                            encryptedSize = 1040L,
                            indexHex = "00".repeat(32),
                            hashHex = "aa".repeat(32),
                            shardUuid = "shard-uuid-1",
                            startedAt = startedAt,
                            clock = { startedAt.plusMillis(500) },
                        )
                    }
                assertEquals(409, ex.statusCode)
                kotlin.test.assertTrue(
                    ex.message?.contains("Some other conflict") == true,
                    "non-MissingUploads 409 propagates unchanged",
                )
                assertEquals(1, finishCalls.get(), "no retry when 409 isn't MissingUploadsError")
                assertEquals(0, listingCalls.get(), "no reconcile listing for non-MissingUploads 409")
            } finally {
                service.close()
            }
        }

    @Test
    fun `401 then 200 replays exactly once after a forced refresh`() =
        kotlinx.coroutines.test.runTest {
            val httpCallCount = AtomicInteger(0)
            val forcedRefreshCount = AtomicInteger(0)
            val nonForcedCallCount = AtomicInteger(0)

            val engine =
                io.ktor.client.engine.mock.MockEngine { _ ->
                    val n = httpCallCount.incrementAndGet()
                    if (n == 1) {
                        respond(
                            content = """{"error":"unauthorized"}""",
                            status = io.ktor.http.HttpStatusCode.Unauthorized,
                            headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond(
                            content = """{"uuid":"folder-uuid","plainName":"name"}""",
                            status = io.ktor.http.HttpStatusCode.OK,
                            headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                        )
                    }
                }
            val service =
                InternxtApiService(
                    InternxtConfig(),
                    { forceRefresh ->
                        if (forceRefresh) forcedRefreshCount.incrementAndGet()
                        else nonForcedCallCount.incrementAndGet()
                        org.krost.unidrive.internxt.model.InternxtCredentials(
                            jwt = if (forceRefresh) "fresh-jwt" else "stale-jwt",
                            mnemonic = "test-mnemonic",
                            rootFolderId = "test-root",
                            email = "test@example.invalid",
                        )
                    },
                )
            val field = InternxtApiService::class.java.getDeclaredField("httpClient")
            field.isAccessible = true
            (field.get(service) as? io.ktor.client.HttpClient)?.close()
            field.set(service, io.ktor.client.HttpClient(engine))

            val folder = service.createFolder("parent-uuid", "name", "encrypted-name")

            assertEquals("folder-uuid", folder.uuid, "post-refresh replay returns the 200 payload")
            assertEquals(2, httpCallCount.get(), "exactly two HTTP attempts: original 401 + replayed 200")
            assertEquals(1, forcedRefreshCount.get(), "exactly one forced refresh on the 401")
            assertEquals(1, nonForcedCallCount.get(), "the pre-401 call resolved creds with forceRefresh=false")
            service.close()
        }

    @Test
    fun `deleteFile retries on 503 and succeeds on the second attempt`() =
        kotlinx.coroutines.test.runTest {
            val httpCalls = AtomicInteger(0)
            val engine =
                io.ktor.client.engine.mock.MockEngine { _ ->
                    val n = httpCalls.incrementAndGet()
                    if (n == 1) {
                        respond(
                            content = """{"error":"service unavailable"}""",
                            status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                            headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                        )
                    } else {
                        respond(
                            content = "",
                            status = io.ktor.http.HttpStatusCode.OK,
                            headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                        )
                    }
                }
            val service = newServiceWithMock(engine)
            try {
                service.deleteFile("file-uuid")
                assertEquals(2, httpCalls.get(), "deleteFile must retry the 503 and succeed on attempt 2")
            } finally {
                service.close()
            }
        }

    /**
     * Internxt upload retry must pin `indexBytes`. Force a transient on
     * the first PUT and on the first finishUpload, then succeed. Captures
     * the PUT ciphertext bodies AND the indexHex passed to finishUpload across
     * attempts; both must be byte-identical to prove that indexBytes / iv /
     * fileKey stayed stable across the retry boundary in `InternxtProvider`.
     *
     * Mocked endpoints (stateful counters):
     *  - POST /v2/buckets/{bucket}/files/start    → fresh descriptor on first call
     *  - PUT  https://shard-host.invalid/put-target → 503 on attempt 1, OK after
     *  - POST /v2/buckets/{bucket}/files/finish   → 503 on attempt 1, OK on attempt 2
     *  - POST /drive/files                         → OK (createFile after commit)
     *
     * Under the chunk-tombstone resume work, the in-process retry loop now
     * reuses the tombstone-cached PUT URL across attempts (no per-attempt
     * `startUpload` re-issue when the URL is still inside `URL_TTL_MS`),
     * and skips the PUT entirely after `PUT_DONE` (idempotent at S3 but
     * a no-op is cheaper). What the test pins is therefore:
     *   - startUpload fires ONCE total (cached for the whole retry window),
     *   - PUT fires twice (the 503 + the success),
     *   - finishUpload fires twice (the 503 + the success),
     *   - indexHex sent to both finishUpload bodies is byte-identical,
     *   - the failed-PUT and success-PUT ciphertexts are byte-identical
     *     (proves indexBytes/iv/fileKey stability — the load-bearing
     *     invariant; if it breaks, every retried upload silently
     *     corrupts on download).
     */
    @Test
    fun `upload retry pins indexBytes across attempts (IV stability)`() =
        kotlinx.coroutines.test.runTest {
            val startCalls = AtomicInteger(0)
            val putAttempts = AtomicInteger(0)
            val capturedPutBodies = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
            val finishAttempts = AtomicInteger(0)
            val capturedFinishIndexHex = java.util.Collections.synchronizedList(mutableListOf<String>())

            val shardUrl = "https://shard-host.invalid/put-target"

            val engine =
                io.ktor.client.engine.mock.MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") -> {
                            val n = startCalls.incrementAndGet()
                            respond(
                                content = """{"uploads":[{"index":0,"uuid":"shard-uuid-$n","url":"$shardUrl"}]}""",
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        url == shardUrl -> {
                            val n = putAttempts.incrementAndGet()
                            // streamingFileBody returns OutgoingContent.WriteChannelContent
                            // (see :app:core http/StreamingUpload.kt); drain it through
                            // a buffer to capture the wire bytes.
                            val bodyBytes = drainOutgoingContent(request.body)
                            capturedPutBodies.add(bodyBytes)
                            if (n == 1) {
                                respond(
                                    content = "",
                                    status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                                    headers = io.ktor.http.headersOf(),
                                )
                            } else {
                                respond(
                                    content = "",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf(),
                                )
                            }
                        }
                        url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") -> {
                            val n = finishAttempts.incrementAndGet()
                            val text =
                                when (val b = request.body) {
                                    is io.ktor.http.content.ByteArrayContent ->
                                        String(b.bytes(), Charsets.UTF_8)
                                    is io.ktor.http.content.TextContent -> b.text
                                    else -> error("unexpected finishUpload body type: ${b.javaClass}")
                                }
                            val parsed = Json.parseToJsonElement(text).jsonObject
                            capturedFinishIndexHex.add(parsed["index"]!!.jsonPrimitive.content)
                            if (n == 1) {
                                respond(
                                    content = """{"error":"service unavailable"}""",
                                    status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            } else {
                                respond(
                                    content = """{"id":"bridge-fileId","index":"${parsed["index"]!!.jsonPrimitive.content}","bucket":"test-bucket","name":"enc-name"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                        }
                        url.startsWith("https://gateway.internxt.com/drive/files") -> {
                            respond(
                                content = """{"uuid":"file-uuid","plainName":"iv-pin-test","type":"bin","size":"32","bucket":"test-bucket","fileId":"bridge-fileId","status":"EXISTS"}""",
                                status = io.ktor.http.HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                            )
                        }
                        else -> error("unexpected URL hit by upload IV-pinning test: $url")
                    }
                }

            // Tombstone-isolated tempDir so this test can't trip over a
            // sidecar from a prior failed run (or leak one to the next).
            val tmpRoot = java.nio.file.Files.createTempDirectory("ud-iv-pin-")
            val provider = newProviderRooted(tmpRoot)
            val tempLocal =
                java.nio.file.Files
                    .createTempFile(tmpRoot, "iv-pin-test-", ".bin")
                    .also { p ->
                        java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
                    }
            try {
                installMockClientOnProvider(provider, engine)
                provider.upload(tempLocal, "/iv-pin-test.bin", existingRemoteId = null, onProgress = null)

                assertEquals(1, startCalls.get(),
                    "startUpload fires once and the URL is cached on the tombstone for the retry window")
                assertEquals(2, putAttempts.get(),
                    "PUT retries once after the attempt-1 503; PUT_DONE pins it so no third PUT on the finish-503 retry")
                assertEquals(2, finishAttempts.get(),
                    "finishUpload retries once after the attempt-1 503")
                assertEquals(2, capturedFinishIndexHex.size, "two finishUpload bodies captured")

                // Primary IV-pinning assertion: indexHex equality across the
                // two finishUpload attempts — the metadata-side proof.
                assertEquals(
                    capturedFinishIndexHex[0],
                    capturedFinishIndexHex[1],
                    "indexHex passed to finishUpload MUST stay byte-identical across retry attempts " +
                        "(server stores it; mismatch = silent corruption on download)",
                )
                kotlin.test.assertEquals(
                    64,
                    capturedFinishIndexHex[0].length,
                    "indexHex must be 64 hex chars (32 bytes)",
                )

                // Secondary IV-pinning assertion: the PUT ciphertexts on the
                // failed attempt (1) and the succeeding attempt (2) must be
                // byte-identical. Equal ciphertexts under AES-CTR prove
                // indexBytes / iv / fileKey ALL stayed stable — any drift in
                // any of them changes the keystream and produces different
                // ciphertext.
                kotlin.test.assertTrue(
                    capturedPutBodies[0].contentEquals(capturedPutBodies[1]),
                    "PUT ciphertext on the failed attempt must equal the succeeding attempt's ciphertext",
                )
            } finally {
                java.nio.file.Files.deleteIfExists(tempLocal)
                provider.close()
                tmpRoot.toFile().deleteRecursively()
            }
        }

    /**
     * Build a provider whose AuthService is pre-populated with credentials that
     * carry a non-empty `bucket` (the upload pipeline requires it) and a
     * far-future JWT exp so getValidCredentials never triggers a real refresh.
     */
    private fun newProviderWithBucketCredentials(): InternxtProvider {
        val provider = InternxtProvider()
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = fakeJwt,
                // BIP39 mnemonic — any valid-shape one works since
                // deriveBucketKey is just a PBKDF2 + SHA pipeline. Use the same
                // canonical 12-word seed phrase the InternxtCrypto tests use.
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                // deriveBucketKey hex-decodes the bucket id, so the test value
                // must be valid hex (matches the InternxtCryptoTest sample).
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    private fun installMockClientOnProvider(
        provider: InternxtProvider,
        engine: io.ktor.client.engine.mock.MockEngine,
    ) {
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(api) as? io.ktor.client.HttpClient)?.close()
        field.set(api, io.ktor.client.HttpClient(engine))
    }

    private suspend fun drainOutgoingContent(content: io.ktor.http.content.OutgoingContent): ByteArray =
        when (content) {
            is io.ktor.http.content.ByteArrayContent -> content.bytes()
            is io.ktor.http.content.TextContent -> content.bytes()
            is io.ktor.http.content.OutgoingContent.ReadChannelContent ->
                content.readFrom().toByteArray()
            is io.ktor.http.content.OutgoingContent.WriteChannelContent ->
                kotlinx.coroutines.coroutineScope {
                    val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                    val writeJob = this.async(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            content.writeTo(channel)
                        } finally {
                            channel.flushAndClose()
                        }
                    }
                    val bytes = channel.toByteArray()
                    writeJob.await()
                    bytes
                }
            else -> error("unexpected OutgoingContent type: ${content.javaClass}")
        }

    // -----------------------------------------------------------------
    // Chunk-tombstone resume tests. The store is sidecar-on-disk under
    // ${config.tokenPath}/upload-tombstones/, so each test gets its own
    // tempDir-rooted config + provider so tombstones can't leak across
    // tests or pollute the developer's real ~/.config/unidrive/.
    // -----------------------------------------------------------------

    /**
     * Build a provider rooted at [tokenPath] (so the tombstone-store sidecar
     * dir is test-local), pre-populated with the same credentials shape that
     * [newProviderWithBucketCredentials] uses.
     */
    private fun newProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = fakeJwt,
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    /**
     * Cheap default mock router for tombstone tests: counters per endpoint,
     * vanilla success responses. Tests that need to inject failures override
     * the engine inline.
     */
    private class TombMockCounters {
        val startCalls = AtomicInteger(0)
        val putCalls = AtomicInteger(0)
        val finishCalls = AtomicInteger(0)
        val createFileCalls = AtomicInteger(0)
        val replaceFileCalls = AtomicInteger(0)
        val listingCalls = AtomicInteger(0)
        val capturedFinishIndexHex = java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    private fun tombMockEngine(
        counters: TombMockCounters,
        shardUrl: String = "https://shard-host.invalid/put-target",
        startUuid: String = "shard-uuid-fresh",
    ): io.ktor.client.engine.mock.MockEngine =
        io.ktor.client.engine.mock.MockEngine { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") -> {
                    counters.startCalls.incrementAndGet()
                    respond(
                        content = """{"uploads":[{"index":0,"uuid":"$startUuid","url":"$shardUrl"}]}""",
                        status = io.ktor.http.HttpStatusCode.OK,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                    )
                }
                url == shardUrl -> {
                    counters.putCalls.incrementAndGet()
                    respond("", io.ktor.http.HttpStatusCode.OK)
                }
                url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") -> {
                    counters.finishCalls.incrementAndGet()
                    val text =
                        when (val b = request.body) {
                            is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                            is io.ktor.http.content.TextContent -> b.text
                            else -> error("unexpected finishUpload body type: ${b.javaClass}")
                        }
                    val parsed = Json.parseToJsonElement(text).jsonObject
                    counters.capturedFinishIndexHex.add(parsed["index"]!!.jsonPrimitive.content)
                    respond(
                        content = """{"id":"bridge-fileId-finish","index":"${parsed["index"]!!.jsonPrimitive.content}","bucket":"test-bucket","name":"enc-name"}""",
                        status = io.ktor.http.HttpStatusCode.OK,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                    )
                }
                url.startsWith("https://gateway.internxt.com/drive/files/") && request.method == io.ktor.http.HttpMethod.Put -> {
                    counters.replaceFileCalls.incrementAndGet()
                    respond(
                        content = """{"uuid":"replaced-uuid","plainName":"resume-test","type":"bin","size":"32","bucket":"test-bucket","fileId":"bridge-fileId","status":"EXISTS"}""",
                        status = io.ktor.http.HttpStatusCode.OK,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                    )
                }
                url.startsWith("https://gateway.internxt.com/drive/files") && request.method == io.ktor.http.HttpMethod.Post -> {
                    counters.createFileCalls.incrementAndGet()
                    respond(
                        content = """{"uuid":"file-uuid","plainName":"resume-test","type":"bin","size":"32","bucket":"test-bucket","fileId":"bridge-fileId","status":"EXISTS"}""",
                        status = io.ktor.http.HttpStatusCode.OK,
                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                    )
                }
                else -> error("unexpected URL hit by tombstone test: $url ($request.method)")
            }
        }

    /**
     * Pre-write a `.enc` ciphertext placeholder for the resume tests that
     * skip re-encrypt. Content doesn't matter — the production code only
     * checks Files.size against the tombstone's encryptedSize.
     */
    private fun seedEnc(
        store: UploadTombstoneStore,
        pathHash: String,
        sizeBytes: Long,
    ) {
        val encPath = store.ciphertextPath(pathHash)
        java.nio.file.Files.createDirectories(encPath.parent)
        java.nio.file.Files.write(encPath, ByteArray(sizeBytes.toInt()) { 0x42 })
    }

    @Test
    fun `tombstone round-trip writes the JSON sidecar and reads back identical bytes`() {
        val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-rt-")
        try {
            val store = UploadTombstoneStore(tmp)
            val pathHash = UploadTombstoneStore.pathHash("/some/file.bin")
            val original =
                UploadTombstone(
                    localPath = "/some/file.bin",
                    localMtimeMillis = 1_700_000_000_000L,
                    localSize = 1024L,
                    bucket = "bucket-1",
                    folderUuid = "folder-uuid-1",
                    plainName = "file",
                    ext = "bin",
                    indexBytesHex = "00".repeat(32),
                    shardUuid = "shard-uuid-1",
                    bridgePutUrl = "https://shard-host/put",
                    encryptedSize = 1040L,
                    hashHex = "aa".repeat(32),
                    existingRemoteId = "remote-uuid-existing",
                    stage = UploadTombstone.Stage.PUT_DONE,
                    startedAtMillis = 1_700_000_000_500L,
                    tombstoneWrittenAtMillis = 1_700_000_000_900L,
                )
            store.write(pathHash, original)
            val readBack = store.read(pathHash)
            assertEquals(original, readBack)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resume at ENCRYPTING re-runs the encrypt with the same indexBytes`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-enc-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                // Seed an ENCRYPTING tombstone — no .enc on disk yet, the
                // resume path will re-encrypt under the SAME indexBytes.
                val pinnedIndex = "11".repeat(32)
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        stage = UploadTombstone.Stage.ENCRYPTING,
                        startedAtMillis = System.currentTimeMillis(),
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    ),
                )

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(1, counters.startCalls.get(), "fresh startUpload on resume from ENCRYPTING (no cached URL)")
                assertEquals(1, counters.putCalls.get(), "PUT runs once after encrypt finishes")
                assertEquals(1, counters.finishCalls.get(), "finishUpload runs once after PUT")
                assertEquals(1, counters.createFileCalls.get(), "createFile runs once on a new path")
                assertEquals(pinnedIndex, counters.capturedFinishIndexHex.single(),
                    "indexHex sent to finishUpload MUST match the tombstone-pinned indexBytes")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `resume at PUT_PENDING with fresh URL re-PUTs cached ciphertext + indexBytes`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-put-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val pinnedIndex = "22".repeat(32)
                val now = System.currentTimeMillis()
                // Seed PUT_PENDING with a fresh-enough URL (<URL_TTL_MS old),
                // shard uuid, and .enc placeholder — code path skips startUpload
                // and re-PUTs directly with the cached descriptor.
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        shardUuid = "shard-uuid-pinned",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "bb".repeat(32),
                        stage = UploadTombstone.Stage.PUT_PENDING,
                        startedAtMillis = now,
                        tombstoneWrittenAtMillis = now,
                    ),
                )
                seedEnc(store, pathHash, 48L)

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(0, counters.startCalls.get(), "startUpload SKIPPED — cached URL still within TTL")
                assertEquals(1, counters.putCalls.get(), "PUT runs once against the cached URL")
                assertEquals(1, counters.finishCalls.get(), "finishUpload runs once after PUT")
                assertEquals(pinnedIndex, counters.capturedFinishIndexHex.single(),
                    "indexHex sent to finishUpload MUST match the tombstone-pinned indexBytes")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `resume at PUT_DONE skips re-PUT and calls finishUpload once`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-putdone-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val pinnedIndex = "33".repeat(32)
                val now = System.currentTimeMillis()
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        shardUuid = "shard-uuid-already-put",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "cc".repeat(32),
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = now,
                        tombstoneWrittenAtMillis = now,
                    ),
                )
                seedEnc(store, pathHash, 48L)

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(0, counters.startCalls.get(), "startUpload SKIPPED on PUT_DONE resume")
                assertEquals(0, counters.putCalls.get(), "PUT SKIPPED on PUT_DONE resume")
                assertEquals(1, counters.finishCalls.get(), "finishUpload runs once")
                assertEquals(1, counters.createFileCalls.get(), "createFile registers the bucket entry")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `resume at FINISH_DONE skips bridge calls and only re-finishes`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-finishdone-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val pinnedIndex = "44".repeat(32)
                val now = System.currentTimeMillis()
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        shardUuid = "shard-uuid-finished",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "dd".repeat(32),
                        stage = UploadTombstone.Stage.FINISH_DONE,
                        startedAtMillis = now,
                        tombstoneWrittenAtMillis = now,
                    ),
                )
                seedEnc(store, pathHash, 48L)

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(0, counters.startCalls.get(), "startUpload SKIPPED on FINISH_DONE resume")
                assertEquals(0, counters.putCalls.get(), "PUT SKIPPED on FINISH_DONE resume")
                // Spec note: FINISH_DONE re-call of finishUpload either succeeds
                // (idempotent at the server's reconcile path) or surfaces 409;
                // our mock returns OK → the inner code re-derives the bucket
                // entry id directly from the second finishUpload response, then
                // proceeds to createFile.
                assertEquals(1, counters.finishCalls.get(), "finishUpload called once on FINISH_DONE resume")
                assertEquals(1, counters.createFileCalls.get(), "createFile registers the bucket entry")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `stale-on-mtime-drift discards the tombstone and rotates indexBytes`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-mtime-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val realMtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val staleIndex = "55".repeat(32)
                // Tombstone says mtime was 1 hour BEFORE the file's actual mtime
                // — drift trips the staleness check; the tombstone discards and
                // a fresh indexBytes is generated.
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = realMtimeMillis - 3_600_000L,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = staleIndex,
                        shardUuid = "shard-uuid-stale",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "ee".repeat(32),
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = System.currentTimeMillis(),
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    ),
                )

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(1, counters.startCalls.get(), "startUpload re-issued on cold restart")
                assertEquals(1, counters.putCalls.get(), "PUT runs once on cold restart")
                assertEquals(1, counters.finishCalls.get(), "finishUpload runs once on cold restart")
                val freshIndex = counters.capturedFinishIndexHex.single()
                kotlin.test.assertNotEquals(staleIndex, freshIndex,
                    "stale tombstone discarded → fresh indexBytes rotated through finishUpload")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `stale-on-size-drift discards the tombstone and rotates indexBytes`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-size-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val staleIndex = "66".repeat(32)
                // localSize on the tombstone (16) ≠ actual file size (32).
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 16L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = staleIndex,
                        shardUuid = "shard-uuid-stale",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 32L,
                        hashHex = "ff".repeat(32),
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = System.currentTimeMillis(),
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    ),
                )

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                val freshIndex = counters.capturedFinishIndexHex.single()
                kotlin.test.assertNotEquals(staleIndex, freshIndex,
                    "size drift → tombstone discard → fresh indexBytes")
                assertEquals(1, counters.startCalls.get())
                assertEquals(1, counters.putCalls.get())
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    /**
     * IV-PINNING REGRESSION (case 8). The most load-bearing test in this set.
     *
     * Pre-write a PUT_PENDING tombstone with `tombstoneWrittenAtMillis` set
     * far enough in the past to exceed `URL_TTL_MS`. The resume path MUST
     * re-issue `startUpload` (to refresh the presigned PUT URL) WITHOUT
     * touching `indexBytes`. That property is the entire reason this work
     * exists: the server stores `indexHex` from finishUpload, and ciphertext
     * encrypted with an old index alongside a new index in metadata is
     * silent corruption on download.
     */
    @Test
    fun `stale-on-URL-TTL re-issues startUpload but preserves indexBytes (IV-pinning regression)`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-urlttl-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val pinnedIndex = "77".repeat(32)
                val staleMillis = System.currentTimeMillis() - (InternxtConfig.URL_TTL_MS + 60_000L)
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        shardUuid = "shard-uuid-expired",
                        bridgePutUrl = "https://shard-host.invalid/expired-target",
                        encryptedSize = 48L,
                        hashHex = "aa".repeat(32),
                        stage = UploadTombstone.Stage.PUT_PENDING,
                        startedAtMillis = staleMillis,
                        tombstoneWrittenAtMillis = staleMillis,
                    ),
                )
                seedEnc(store, pathHash, 48L)

                val counters = TombMockCounters()
                // Mock returns a FRESH startUpload URL — verifies the resume
                // re-issued startUpload even though shardUuid+URL were on the
                // tombstone. The OLD URL hostname doesn't appear in routing.
                installMockClientOnProvider(provider, tombMockEngine(counters, startUuid = "shard-uuid-fresh-after-ttl"))
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(1, counters.startCalls.get(), "URL TTL exceeded → fresh startUpload")
                assertEquals(1, counters.putCalls.get(), "PUT runs once against the fresh URL")
                assertEquals(1, counters.finishCalls.get(), "finishUpload runs once after PUT")

                // THE load-bearing assertion: the indexHex sent to finishUpload
                // — which is what the server records and what the file is
                // decrypted under on every future download — MUST equal the
                // pre-staged pinnedIndex byte-for-byte.
                assertEquals(
                    pinnedIndex,
                    counters.capturedFinishIndexHex.single(),
                    "indexHex MUST stay pinned across a URL-TTL refresh. " +
                        "If this fails, every resumed upload after URL expiry is silently corrupted: " +
                        "the bridge stores a fresh indexBytes alongside ciphertext encrypted with the original, " +
                        "and downloads decrypt to garbage.",
                )
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `stale-on-existingRemoteId-mismatch discards the tombstone`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-rid-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val staleIndex = "88".repeat(32)
                // Tombstone was written for a MODIFIED upload; the new call
                // comes in as NEW (existingRemoteId=null) — sync DB drift, must
                // not adopt the prior MODIFIED-arc work for a fresh CREATE.
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = staleIndex,
                        shardUuid = "shard-uuid-stale",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "aa".repeat(32),
                        existingRemoteId = "remote-uuid-prior",
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = System.currentTimeMillis(),
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    ),
                )

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                // Pass existingRemoteId=null — mismatch with the tombstone.
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                val freshIndex = counters.capturedFinishIndexHex.single()
                kotlin.test.assertNotEquals(staleIndex, freshIndex,
                    "existingRemoteId mismatch → tombstone discard → fresh indexBytes")
                assertEquals(1, counters.startCalls.get())
                assertEquals(1, counters.createFileCalls.get(), "NEW call routes through createFile (not replaceFile)")
                assertEquals(0, counters.replaceFileCalls.get())
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `409-reconcile on resumed finishUpload uses the original startedAt instant`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-409-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                val pinnedIndex = "99".repeat(32)
                // The tombstone was created 4 minutes ago — well within the
                // 5-minute reconcile window of commitWithRetry. A resume that
                // "now" started a NEW startedAt would put now=t+4m and only
                // accept commits AFTER t+4m-5m = t-1m (which is fine, but the
                // production code passes the TOMBSTONE startedAt — the
                // original t — and accepts commits AFTER t-5m. That's the
                // invariant.). The listing-reconcile returns a creationTime
                // exactly between t-5m and t (i.e. t-2m); under the wrong
                // (now-anchored) window this would be REJECTED (NotFoundWithNameMatch),
                // and the test would surface a 409 surfacing. Under the
                // correct (tombstone-anchored) window it's ACCEPTED → returns
                // the reconciled bucket entry.
                val tombStarted = System.currentTimeMillis() - 4 * 60_000L
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = pinnedIndex,
                        shardUuid = "shard-uuid-pre-409",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "bb".repeat(32),
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = tombStarted,
                        tombstoneWrittenAtMillis = tombStarted,
                    ),
                )
                seedEnc(store, pathHash, 48L)

                val plain = local.fileName.toString().substringBeforeLast('.')
                val finishCalls = AtomicInteger(0)
                val listingCalls = AtomicInteger(0)
                val createCalls = AtomicInteger(0)
                // Creation time exactly between tombStarted-5m and tombStarted
                // (i.e. tombStarted-2m). Within window=tombStarted-5m, OK.
                val reconciledCreationTime =
                    java.time.Instant.ofEpochMilli(tombStarted - 2 * 60_000L).toString()

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                                finishCalls.incrementAndGet()
                                respond(
                                    content = """{"statusCode":409,"message":"MissingUploadsError","error":"Conflict"}""",
                                    status = io.ktor.http.HttpStatusCode.Conflict,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.contains("/folders/content/root-folder-uuid") -> {
                                listingCalls.incrementAndGet()
                                respond(
                                    content = """{"children":[],"files":[{
                                        "uuid":"resolved-uuid",
                                        "fileId":"bridge-fileId-reconciled",
                                        "plainName":"$plain",
                                        "type":"bin",
                                        "size":"32",
                                        "bucket":"test-bucket",
                                        "status":"EXISTS",
                                        "creationTime":"$reconciledCreationTime"
                                    }]}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.startsWith("https://gateway.internxt.com/drive/files") && request.method == io.ktor.http.HttpMethod.Post -> {
                                createCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"file-uuid","plainName":"$plain","type":"bin","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-reconciled","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            else -> error("unexpected URL: $url ($request.method)")
                        }
                    }
                installMockClientOnProvider(provider, engine)
                provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)

                assertEquals(1, finishCalls.get(), "finishUpload returns 409 once")
                assertEquals(1, listingCalls.get(),
                    "reconcile listing fires once and finds the candidate (proves the t-startedAt window was honoured)")
                assertEquals(1, createCalls.get(),
                    "after reconcile success, register the bucket entry on drive metadata")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `missing local path during resume cleans up the tombstone`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-tomb-missing-")
            val local = java.nio.file.Files.createTempFile(tmp, "src-", ".bin").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
                val pathHash = UploadTombstoneStore.pathHash(local.toAbsolutePath().toString())
                val mtimeMillis = java.nio.file.Files.getLastModifiedTime(local).toMillis()
                store.write(
                    pathHash,
                    UploadTombstone(
                        localPath = local.toAbsolutePath().toString(),
                        localMtimeMillis = mtimeMillis,
                        localSize = 32L,
                        bucket = "test-bucket",
                        folderUuid = "root-folder-uuid",
                        plainName = local.fileName.toString().substringBeforeLast('.'),
                        ext = "bin",
                        indexBytesHex = "aa".repeat(32),
                        shardUuid = "shard-uuid-orphan",
                        bridgePutUrl = "https://shard-host.invalid/put-target",
                        encryptedSize = 48L,
                        hashHex = "bb".repeat(32),
                        stage = UploadTombstone.Stage.PUT_DONE,
                        startedAtMillis = System.currentTimeMillis(),
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    ),
                )
                seedEnc(store, pathHash, 48L)
                kotlin.test.assertNotNull(store.read(pathHash))

                // Delete the local file. Upload should fail with NoSuchFileException
                // and the tombstone (plus its .enc) must be discarded.
                java.nio.file.Files.delete(local)

                val counters = TombMockCounters()
                installMockClientOnProvider(provider, tombMockEngine(counters))
                try {
                    provider.upload(local, "/${local.fileName}", existingRemoteId = null, onProgress = null)
                    kotlin.test.fail("expected NoSuchFileException — local file was deleted")
                } catch (_: java.nio.file.NoSuchFileException) {
                    // expected
                }
                kotlin.test.assertNull(store.read(pathHash),
                    "missing local path on a tombstone → discard the sidecar (and .enc)")
                kotlin.test.assertFalse(
                    java.nio.file.Files.exists(store.ciphertextPath(pathHash)),
                    "ciphertext temp file must also be cleaned up",
                )
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    // -----------------------------------------------------------------
    // keep_overwritten destructive-overwrite guard. Same provider-with-
    // bucket-credentials shape as the tombstone tests; replace branch
    // routes through rename-then-create when the config opts in.
    // -----------------------------------------------------------------

    /** Mirror of [newProviderRooted] but with `keepOverwritten = true`. */
    private fun newKeepOverwrittenProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath, keepOverwritten = true))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = fakeJwt,
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    @Test
    fun `upload modify with keepOverwritten=false calls replaceFile directly`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-kov-off-")
            // Local source file with a deterministic name so plainName=foo, ext=txt.
            val local = tmp.resolve("foo.txt").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newProviderRooted(tmp)
                val renameMetaCalls = AtomicInteger(0)
                val replaceCalls = AtomicInteger(0)
                val createCalls = AtomicInteger(0)
                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"https://shard-host.invalid/put"}]}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url == "https://shard-host.invalid/put" ->
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"bridge-fileId-new","index":"00","bucket":"test-bucket","name":"enc-name"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                renameMetaCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"renamed","type":"txt","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-old"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.endsWith("/drive/files/remote-uuid-existing") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                replaceCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"foo","type":"txt","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-new"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.endsWith("/drive/files") && request.method == io.ktor.http.HttpMethod.Post -> {
                                createCalls.incrementAndGet()
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            }
                            else -> error("unexpected URL: $url (${request.method})")
                        }
                    }
                installMockClientOnProvider(provider, engine)
                provider.upload(local, "/foo.txt", existingRemoteId = "remote-uuid-existing", onProgress = null)
                assertEquals(0, renameMetaCalls.get(), "keepOverwritten=false MUST NOT rename the prior file")
                assertEquals(1, replaceCalls.get(), "keepOverwritten=false routes through PUT /files/{uuid}")
                assertEquals(0, createCalls.get(), "keepOverwritten=false MUST NOT POST a new file")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `upload modify with keepOverwritten=true renames before creating`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-kov-on-")
            val local = tmp.resolve("foo.txt").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newKeepOverwrittenProviderRooted(tmp)
                val getMetaCalls = AtomicInteger(0)
                val renameMetaCalls = AtomicInteger(0)
                val replaceCalls = AtomicInteger(0)
                val createCalls = AtomicInteger(0)
                val capturedRenamePlainName = java.util.Collections.synchronizedList(mutableListOf<String>())
                val capturedCreatePlainName = java.util.Collections.synchronizedList(mutableListOf<String>())
                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"https://shard-host.invalid/put"}]}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url == "https://shard-host.invalid/put" ->
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"bridge-fileId-new","index":"00","bucket":"test-bucket","name":"enc-name"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Get -> {
                                getMetaCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"foo","type":"txt","size":"16","bucket":"test-bucket","fileId":"bridge-fileId-old","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                renameMetaCalls.incrementAndGet()
                                val text =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.TextContent -> b.text
                                        is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                                        else -> error("unexpected rename body: ${b.javaClass}")
                                    }
                                val parsed = Json.parseToJsonElement(text).jsonObject
                                capturedRenamePlainName.add(parsed["plainName"]!!.jsonPrimitive.content)
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"${parsed["plainName"]!!.jsonPrimitive.content}","type":"txt","size":"16","bucket":"test-bucket","fileId":"bridge-fileId-old"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.endsWith("/drive/files/remote-uuid-existing") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                replaceCalls.incrementAndGet()
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            }
                            url.endsWith("/drive/files") && request.method == io.ktor.http.HttpMethod.Post -> {
                                createCalls.incrementAndGet()
                                val text =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.TextContent -> b.text
                                        is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                                        else -> error("unexpected create body: ${b.javaClass}")
                                    }
                                val parsed = Json.parseToJsonElement(text).jsonObject
                                capturedCreatePlainName.add(parsed["plainName"]!!.jsonPrimitive.content)
                                respond(
                                    content = """{"uuid":"new-file-uuid","plainName":"foo","type":"txt","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-new","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            else -> error("unexpected URL: $url (${request.method})")
                        }
                    }
                installMockClientOnProvider(provider, engine)
                provider.upload(local, "/foo.txt", existingRemoteId = "remote-uuid-existing", onProgress = null)

                assertEquals(1, getMetaCalls.get(), "must read prior file metadata to learn plainName + type")
                assertEquals(1, renameMetaCalls.get(), "must rename existing UUID to archive plainName")
                assertEquals(1, createCalls.get(), "must create the new file under the original plainName")
                assertEquals(0, replaceCalls.get(), "rename-and-create path MUST NOT call destructive PUT /files/{uuid}")

                val renamed = capturedRenamePlainName.single()
                val archiveRegex = Regex("""^foo\.unidrive-prev-\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}$""")
                kotlin.test.assertTrue(
                    archiveRegex.matches(renamed),
                    "archive plainName must match $archiveRegex; got '$renamed'",
                )
                assertEquals("foo", capturedCreatePlainName.single(), "createFile must keep the original plainName")
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `upload modify with keepOverwritten=true and rename collision appends counter`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-kov-collision-")
            val local = tmp.resolve("foo.txt").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            try {
                val provider = newKeepOverwrittenProviderRooted(tmp)
                val renameAttempts = AtomicInteger(0)
                val capturedRenamePlainName = java.util.Collections.synchronizedList(mutableListOf<String>())
                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"https://shard-host.invalid/put"}]}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url == "https://shard-host.invalid/put" ->
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"bridge-fileId-new","index":"00","bucket":"test-bucket","name":"enc-name"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Get ->
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"foo","type":"txt","size":"16","bucket":"test-bucket","fileId":"bridge-fileId-old","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                val attempt = renameAttempts.incrementAndGet()
                                val text =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.TextContent -> b.text
                                        is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                                        else -> error("unexpected rename body: ${b.javaClass}")
                                    }
                                val parsed = Json.parseToJsonElement(text).jsonObject
                                capturedRenamePlainName.add(parsed["plainName"]!!.jsonPrimitive.content)
                                if (attempt == 1) {
                                    // First rename collides — same wall-clock-second replace.
                                    respond(
                                        content = """{"statusCode":409,"message":"plainName already in use"}""",
                                        status = io.ktor.http.HttpStatusCode.Conflict,
                                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                    )
                                } else {
                                    // Counter-suffixed retry wins.
                                    respond(
                                        content = """{"uuid":"remote-uuid-existing","plainName":"${parsed["plainName"]!!.jsonPrimitive.content}","type":"txt","size":"16","bucket":"test-bucket","fileId":"bridge-fileId-old"}""",
                                        status = io.ktor.http.HttpStatusCode.OK,
                                        headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                    )
                                }
                            }
                            url.endsWith("/drive/files") && request.method == io.ktor.http.HttpMethod.Post ->
                                respond(
                                    content = """{"uuid":"new-file-uuid","plainName":"foo","type":"txt","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-new","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            else -> error("unexpected URL: $url (${request.method})")
                        }
                    }
                installMockClientOnProvider(provider, engine)
                provider.upload(local, "/foo.txt", existingRemoteId = "remote-uuid-existing", onProgress = null)

                assertEquals(2, renameAttempts.get(), "expected one collision then a counter-suffixed retry")
                val secondAttempt = capturedRenamePlainName[1]
                kotlin.test.assertTrue(
                    secondAttempt.endsWith("-2"),
                    "second rename plainName must end with '-2' counter suffix; got '$secondAttempt'",
                )
            } finally {
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `upload modify with keepOverwritten=true falls through to destructive replace on rename failure`() =
        kotlinx.coroutines.test.runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-kov-fail-")
            val local = tmp.resolve("foo.txt").also { p ->
                java.nio.file.Files.write(p, ByteArray(32) { it.toByte() })
            }
            // Capture the warn log emitted by the fall-through path.
            val providerLogger =
                org.slf4j.LoggerFactory.getLogger(InternxtProvider::class.java)
                    as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            providerLogger.addAppender(appender)
            try {
                val provider = newKeepOverwrittenProviderRooted(tmp)
                val replaceCalls = AtomicInteger(0)
                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"https://shard-host.invalid/put"}]}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url == "https://shard-host.invalid/put" ->
                                respond("", io.ktor.http.HttpStatusCode.OK)
                            url.startsWith("https://api.internxt.com/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"bridge-fileId-new","index":"00","bucket":"test-bucket","name":"enc-name"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Get ->
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"foo","type":"txt","size":"16","bucket":"test-bucket","fileId":"bridge-fileId-old","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing/meta") &&
                                request.method == io.ktor.http.HttpMethod.Put ->
                                // Permanent 500 — non-transient retries inside renameFile exhaust,
                                // bubble back to the guard which logs warn + falls through.
                                respond(
                                    content = """{"statusCode":500,"message":"boom"}""",
                                    status = io.ktor.http.HttpStatusCode.InternalServerError,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            url.endsWith("/drive/files/remote-uuid-existing") &&
                                request.method == io.ktor.http.HttpMethod.Put -> {
                                replaceCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"remote-uuid-existing","plainName":"foo","type":"txt","size":"32","bucket":"test-bucket","fileId":"bridge-fileId-new","folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                                    status = io.ktor.http.HttpStatusCode.OK,
                                    headers = io.ktor.http.headersOf("Content-Type", "application/json"),
                                )
                            }
                            else -> error("unexpected URL: $url (${request.method})")
                        }
                    }
                installMockClientOnProvider(provider, engine)
                provider.upload(local, "/foo.txt", existingRemoteId = "remote-uuid-existing", onProgress = null)

                assertEquals(1, replaceCalls.get(),
                    "rename failure must NOT strand the edit — destructive replace runs as fallback")
                val warn =
                    appender.list.firstOrNull {
                        it.level == ch.qos.logback.classic.Level.WARN &&
                            it.formattedMessage.contains("keep_overwritten")
                    }
                kotlin.test.assertNotNull(
                    warn,
                    "expected a WARN-level fall-through message; got: ${appender.list.map { it.formattedMessage }}",
                )
            } finally {
                providerLogger.detachAppender(appender)
                appender.stop()
                java.nio.file.Files.deleteIfExists(local)
                tmp.toFile().deleteRecursively()
            }
        }

    // Internxt request prioritization: two-lane Foreground/Background overlay
    // on HttpRetryBudget plus monotonic promotion through InFlightDedup. The
    // following four tests pin the spec's invariants.

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `foreground call jumps the queue ahead of background callers`() =
        kotlinx.coroutines.test.runTest {
            // Eight background callers parked in the yield loop because a
            // foreground admission landed at t=0; one fresh foreground caller
            // must be admitted before any of them clear the loop. Drive the
            // budget's clock off the test scheduler so delay()s resolve
            // against the same virtual time the budget reads.
            val budget =
                org.krost.unidrive.http.HttpRetryBudget(
                    maxConcurrency = 8,
                    minSpacingMs = 500L,
                    clock = { testScheduler.currentTime },
                )
            // Seed a foreground admission so the background yield loop has
            // something to wait on (sets lastForegroundAdmissionMs).
            budget.awaitSlot(org.krost.unidrive.http.Priority.Foreground)

            val admissionOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
            val bgJobs =
                (1..8).map { i ->
                    async {
                        budget.awaitSlot(org.krost.unidrive.http.Priority.Background)
                        admissionOrder.add("bg-$i")
                    }
                }
            // Yield once so all 8 background callers reach the spin loop and
            // suspend on delay(yieldWindowMs). They will be parked because
            // lastForegroundAdmissionMs is fresh (within the 500ms window).
            testScheduler.runCurrent()

            // Now fire one foreground caller. It must skip the lane gate
            // entirely. Drain the scheduler enough for any token-bucket
            // spacing wait to resolve and the foreground caller to admit
            // — but stop short of letting the yield window expire so the
            // background callers are still parked when we assert order.
            val fgJob =
                async {
                    budget.awaitSlot(org.krost.unidrive.http.Priority.Foreground)
                    admissionOrder.add("fg")
                }
            // Advance just past minSpacingMs (500ms) so token-bucket spacing
            // elapses for the foreground caller. The background yield window
            // (yieldWindowMs=minSpacingMs=500ms) elapses at the same moment,
            // so we cap at 499ms to keep background parked.
            testScheduler.advanceTimeBy(499)
            testScheduler.runCurrent()

            kotlin.test.assertTrue(
                "fg" in admissionOrder,
                "foreground caller must have admitted while background callers were still parked",
            )
            kotlin.test.assertEquals(
                "fg",
                admissionOrder.first(),
                "foreground caller must be admitted before any background caller",
            )

            // Drain the rest deterministically.
            testScheduler.advanceUntilIdle()
            bgJobs.awaitAll()
        }

    @Test
    fun `dedup promotes a background load to foreground when a foreground caller joins`() =
        kotlinx.coroutines.test.runTest {
            // Pin the InFlightDedup priority-promotion contract: a foreground
            // caller arriving at an in-flight background key flips the shared
            // AtomicReference to Foreground; the winner's loader, parked
            // inside HttpRetryBudget.awaitSlot's spin loop on a Promotable
            // Priority element, must break out the moment it sees the flip.
            //
            // Observe the flip via currentPriority() read from inside the
            // winner's loader — Promotable resolves on each call, so the
            // value at release time reflects the latest joiner.
            val dedup = org.krost.unidrive.http.InFlightDedup<String, String>()
            val winnerEntered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val observedPriorityAtRelease =
                java.util.concurrent.atomic
                    .AtomicReference<org.krost.unidrive.http.Priority?>(null)

            val winner =
                async {
                    dedup.load("uuid-A", org.krost.unidrive.http.Priority.Background) {
                        winnerEntered.complete(Unit)
                        gate.await()
                        // Read priority from coroutine context *after* the
                        // joiner has promoted — must be Foreground.
                        observedPriorityAtRelease.set(org.krost.unidrive.http.currentPriority())
                        "result"
                    }
                }
            // Drain so the winner's loader starts and parks on gate.await().
            testScheduler.runCurrent()
            winnerEntered.await()

            // Foreground caller joins the same key.
            val joiner =
                async {
                    dedup.load("uuid-A", org.krost.unidrive.http.Priority.Foreground) {
                        kotlin.test.fail("joiner's loader must not run — winner is in flight")
                    }
                }
            // Drain so the joiner reaches the inFlight[key] fast path and
            // promotes the shared reference.
            testScheduler.runCurrent()

            // Release the winner; both callers observe the same result.
            gate.complete(Unit)
            kotlin.test.assertEquals("result", winner.await())
            kotlin.test.assertEquals("result", joiner.await())
            // The winner's coroutine context (Priority.Promotable wrapping the
            // shared AtomicReference) resolves to Foreground at release time.
            kotlin.test.assertEquals(
                org.krost.unidrive.http.Priority.Foreground,
                observedPriorityAtRelease.get(),
                "winner's currentPriority() must reflect the post-promotion lane",
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `background starves under continuously claimed foreground (strict priority)`() =
        kotlinx.coroutines.test.runTest {
            // Strict priority: as long as a foreground slot has been claimed
            // within the last yieldWindowMs, no background caller is admitted.
            // Hold one foreground caller continuously claiming slots and
            // assert background never makes it through within 60s of virtual
            // time. Release the foreground driver and background must admit
            // within one yieldWindowMs.
            val budget =
                org.krost.unidrive.http.HttpRetryBudget(
                    maxConcurrency = 8,
                    minSpacingMs = 500L,
                    clock = { testScheduler.currentTime },
                )
            val stopFg =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val bgAdmitted =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            // Foreground driver: claim a slot, sleep less than yieldWindowMs,
            // repeat. Each admission stamps lastForegroundAdmissionMs.
            val fgJob =
                async {
                    while (!stopFg.get()) {
                        budget.awaitSlot(org.krost.unidrive.http.Priority.Foreground)
                        // Sleep less than the yield window so background never
                        // sees a quiet gap. minSpacingMs=500 ⇒ stay under 500.
                        kotlinx.coroutines.delay(200L)
                    }
                }
            val bgJob =
                async {
                    budget.awaitSlot(org.krost.unidrive.http.Priority.Background)
                    bgAdmitted.set(true)
                }

            // Advance virtual time by 60 s; the test scheduler drives both
            // delay() resolution and the budget's clock through the
            // testScheduler.currentTime closure.
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            kotlin.test.assertFalse(
                bgAdmitted.get(),
                "background must starve while foreground holds the lane (advanced 60s of virtual time)",
            )

            // Release foreground and assert background admits within one yield window.
            stopFg.set(true)
            fgJob.cancelAndJoin()
            // Advance past the yield window (minSpacingMs=500).
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            bgJob.await()
            kotlin.test.assertTrue(
                bgAdmitted.get(),
                "background must admit within one yieldWindowMs of the last foreground admission",
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `storm detector and spacing apply uniformly across priority lanes`() =
        kotlinx.coroutines.test.runTest {
            // One storm, both lanes feel it: a foreground-driven 503 widens
            // the budget's spacing to stormSpacingMs, and a subsequent
            // background call sees the same spacing — the storm coordinator
            // is global, the priority overlay does not partition it.
            val budget =
                org.krost.unidrive.http.HttpRetryBudget(
                    maxConcurrency = 4,
                    stormThreshold = 1,
                    minSpacingMs = 200L,
                    stormSpacingMs = 1_000L,
                    clock = { testScheduler.currentTime },
                )
            kotlin.test.assertEquals(0L, budget.currentSpacingMs(), "pre-storm: steady-state fast path")

            // Foreground-driven throttle. recordThrottle is lane-agnostic and
            // does not consult Priority.
            budget.recordThrottle(retryAfterMs = 2_000L)
            val fgSpacing = budget.currentSpacingMs()
            kotlin.test.assertTrue(
                fgSpacing > 0L,
                "post-503 in foreground context must widen the budget spacing",
            )

            // Now read the same currentSpacingMs() — it must be identical for
            // background callers (the budget is single, not per-lane).
            kotlin.test.assertEquals(
                fgSpacing,
                budget.currentSpacingMs(),
                "background lane must observe the same global spacing as foreground",
            )

            // Exercise both lanes through awaitSlot to confirm the storm
            // gate (circuit + spacing) applies regardless of priority. Both
            // wait out the circuit-breaker pause (stormBackoffFactor=1.2 ×
            // retryAfterMs=2400ms). Drain the scheduler.
            val fgAdmit =
                async {
                    budget.awaitSlot(org.krost.unidrive.http.Priority.Foreground)
                }
            val bgAdmit =
                async {
                    budget.awaitSlot(org.krost.unidrive.http.Priority.Background)
                }
            testScheduler.advanceUntilIdle()
            fgAdmit.await()
            bgAdmit.await()
        }
}
