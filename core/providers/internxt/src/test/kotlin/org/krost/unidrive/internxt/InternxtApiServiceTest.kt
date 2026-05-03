package org.krost.unidrive.internxt

import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.Mirror
import org.krost.unidrive.internxt.model.StartUploadResponse
import java.util.Base64
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
    fun `delta cursor rewinds 6 hours`() {
        val cursor = "2026-03-29T18:30:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-29T12:30:00Z", rewound)
    }

    @Test
    fun `delta cursor rewind crosses midnight`() {
        val cursor = "2026-03-29T03:00:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-28T21:00:00Z", rewound)
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
        InternxtApiService(InternxtConfig()) {
            // Tests don't actually call any HTTP method that needs creds —
            // they exercise the retry/parse helpers directly. Surface a
            // sentinel so any accidental real call fails loudly.
            error("test fixture: credentialsProvider must not be called")
        }

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
    fun `UD-358 listing query params include sort=uuid and order=ASC`() {
        // Without sort/order, paginated /files and /folders walks can drop or
        // duplicate rows on concurrent mutation. uuid is the only stable sort
        // key (immutable per row); order=ASC is conventional.
        val params = InternxtApiService.listingQueryParams(updatedAt = null, limit = 50, offset = 0)
        assertEquals("uuid", params["sort"])
        assertEquals("ASC", params["order"])
        assertEquals("ALL", params["status"], "tombstones must remain visible")
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
}
