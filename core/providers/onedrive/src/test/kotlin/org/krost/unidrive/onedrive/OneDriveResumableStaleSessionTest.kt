package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Silent-data-loss regression: a resumable (>4 MiB) OneDrive upload must NOT resume a
 * stored session against a local file that changed between the interrupted attempt and
 * the resume. Resuming at the server's committed offset into different local bytes
 * assembles OLD[0..offset) + NEW[offset..) on the remote — silent corruption.
 *
 * Invariant: the stored session is bound to the local file's (size, mtime). On any
 * mismatch the stale session is discarded and a fresh createUploadSession is issued;
 * only an exact match resumes.
 */
class OneDriveResumableStaleSessionTest {
    private lateinit var tokenDir: Path
    private val storedUrl = "https://upload.example/stale-session"

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-stale-session")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(id: String, name: String, size: Long): String =
        """{"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},
            "parentReference":{"path":"/drive/root:"}}""".trimIndent()

    /** Build a GraphApiService whose token dir (= session store dir) is [tokenDir] and whose transport is [engine]. */
    private fun graphWith(engine: MockEngine): GraphApiService {
        val svc = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), tokenProvider = { _ -> "test-token" })
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(svc) as? HttpClient)?.close()
        httpClientField.set(svc, HttpClient(engine))
        return svc
    }

    private fun writeLocalFile(size: Int, fill: Byte): Path {
        val f = Files.createTempFile("ud-stale-local", ".bin")
        Files.write(f, ByteArray(size) { fill })
        return f
    }

    @Test
    fun `stale session is discarded and a fresh createUploadSession is issued when the local file changed`() =
        runTest {
            val sizeNew = 5 * 1024 * 1024 // >4 MiB → chunked path
            val local = writeLocalFile(sizeNew, 1)
            // Seed a session whose recorded identity does NOT match the current local file
            // (the interrupted attempt was for a different/older revision).
            UploadSessionStore(tokenDir).put(
                "/big.bin",
                storedUrl,
                Instant.now().plusSeconds(3600),
                localSize = 4_000_000L, // deliberately different size
                localMtimeMillis = 1L, // deliberately different mtime
            )

            var createSessionCount = 0
            var probedStaleUrl = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.toString().startsWith(storedUrl) -> {
                            probedStaleUrl = true
                            // If the buggy code probes the stale session it would resume at offset 2 MiB.
                            respond(
                                """{"nextExpectedRanges":["2097152-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        }
                        request.method == HttpMethod.Put && request.url.toString().startsWith(storedUrl) ->
                            // Buggy resume would PUT the remaining bytes here and "succeed" with a DriveItem —
                            // so the failure surfaces cleanly as createSessionCount==0, not a deserialize error.
                            respond(driveItemJson("big-id", "big.bin", sizeNew.toLong()), HttpStatusCode.Created, jsonHeaders())
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            createSessionCount++
                            respond(
                                """{"uploadUrl":"https://upload.example/fresh","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        }
                        request.url.toString().startsWith("https://upload.example/fresh") ->
                            respond(driveItemJson("big-id", "big.bin", sizeNew.toLong()), HttpStatusCode.Created, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val svc = graphWith(engine)

            svc.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace")

            assertEquals(
                1,
                createSessionCount,
                "DATA LOSS: a changed local file must force a fresh createUploadSession, not resume a stale offset",
            )
            assertTrue(probedStaleUrl == false || createSessionCount == 1) // probe is fine only if it still rebuilds
            svc.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `matching session is reused and no fresh createUploadSession is issued`() =
        runTest {
            val size = 5 * 1024 * 1024
            val local = writeLocalFile(size, 7)
            val mtime = Files.getLastModifiedTime(local).toMillis()
            UploadSessionStore(tokenDir).put(
                "/big.bin",
                storedUrl,
                Instant.now().plusSeconds(3600),
                localSize = size.toLong(),
                localMtimeMillis = mtime,
            )

            var createSessionCount = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.toString().startsWith(storedUrl) ->
                            // Status probe: nothing committed yet, resume from 0.
                            respond(
                                """{"nextExpectedRanges":["0-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        request.method == HttpMethod.Put && request.url.toString().startsWith(storedUrl) ->
                            // Chunk PUT against the reused session completes the upload.
                            respond(driveItemJson("big-id", "big.bin", size.toLong()), HttpStatusCode.Created, jsonHeaders())
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            createSessionCount++
                            respond(
                                """{"uploadUrl":"https://upload.example/fresh","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        }
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val svc = graphWith(engine)

            svc.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace")

            assertEquals(0, createSessionCount, "a matching local file must reuse the stored session, not rebuild it")
            svc.close()
            Files.deleteIfExists(local)
        }
}
