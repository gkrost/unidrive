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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-366 data-loss regression on OneDrive: create-collision must never blind-overwrite.
 *
 * The simple-PUT and upload-session paths hard-coded `@microsoft.graph.conflictBehavior=replace`
 * for *every* upload, so a NEW file (no local lineage to the remote — `existingRemoteId == null`)
 * landing on a path that already holds a different remote silently OVERWROTE it. Same shape as the
 * Internxt UD-366 409→replaceFile data loss.
 *
 * Invariant: `replace` is only legitimate for an UPDATE (we own the remote, `existingRemoteId != null`).
 * A create uploads with `fail`; on a 409 nameAlreadyExists the pre-existing remote is preserved and the
 * local copy is kept-both via `rename`.
 *
 * Four orthogonal invariants, one test each:
 *  1. create-collision keeps both and NEVER issues conflictBehavior=replace (the data-loss regression).
 *  2. an update (existingRemoteId != null) still uses replace-in-place.
 *  3. a create with no collision uploads with `fail` and succeeds with no keep-both retry.
 *  4. the chunked (>4 MiB) create path opens its upload session with `fail`, not `replace`.
 */
class OneDriveCreateCollisionTest {
    private fun driveItemJson(
        id: String,
        name: String,
        size: Long = 5,
    ): String =
        """
        {"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},
         "parentReference":{"path":"/drive/root:"}}
        """.trimIndent()

    /** Build a real OneDriveProvider whose GraphApiService transport is the supplied MockEngine. */
    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider()
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

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `create collision keeps both and never overwrites the pre-existing remote`() =
        runTest {
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            val cb = request.url.parameters["@microsoft.graph.conflictBehavior"]
                            conflictBehaviors += cb
                            if (cb == "fail") {
                                // Server: a different remote already lives at this path.
                                respond(
                                    content = """{"error":{"code":"nameAlreadyExists","message":"name exists"}}""",
                                    status = HttpStatusCode.Conflict,
                                    headers = jsonHeaders(),
                                )
                            } else {
                                // keep-both rename landed (or, in the buggy code, a blind replace).
                                respond(driveItemJson("kept-both-id", "AGENTS 1.md"), HttpStatusCode.OK, jsonHeaders())
                            }
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("kept-both-id", "AGENTS 1.md"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-collision", ".md")
            Files.write(local, "hello".toByteArray())

            // existingRemoteId == null → a NEW file the engine believes doesn't exist remotely.
            val result = provider.upload(local, "/AGENTS.md", existingRemoteId = null)

            assertFalse(
                conflictBehaviors.contains("replace"),
                "DATA LOSS: a create must never PUT with conflictBehavior=replace; saw $conflictBehaviors",
            )
            assertEquals(
                listOf<String?>("fail", "rename"),
                conflictBehaviors,
                "create must try fail first, then keep-both via rename on 409",
            )
            assertEquals("AGENTS 1.md", result.name, "result reflects the kept-both copy, not an overwrite")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `update with existing remote id replaces in place`() =
        runTest {
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            conflictBehaviors += request.url.parameters["@microsoft.graph.conflictBehavior"]
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-update", ".txt")
            Files.write(local, "world".toByteArray())

            // existingRemoteId != null → a MODIFIED file we own → replace-in-place is correct.
            provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(listOf<String?>("replace"), conflictBehaviors, "an update must replace-in-place")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `create without collision uploads with fail and does not retry`() =
        runTest {
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            conflictBehaviors += request.url.parameters["@microsoft.graph.conflictBehavior"]
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-fresh", ".txt")
            Files.write(local, "data".toByteArray())

            val result = provider.upload(local, "/fresh.txt", existingRemoteId = null)

            assertEquals(listOf<String?>("fail"), conflictBehaviors, "a non-colliding create uses fail with no retry")
            assertEquals("fresh.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `large file create opens its upload session with fail not replace`() =
        runTest {
            var sessionBody: String? = null
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            sessionBody =
                                (request.body as io.ktor.http.content.TextContent).text
                            respond(
                                content = """{"uploadUrl":"https://upload.example/session","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                status = HttpStatusCode.OK,
                                headers = jsonHeaders(),
                            )
                        }
                        request.url.toString().startsWith("https://upload.example/session") ->
                            // Single-chunk commit completes the upload.
                            respond(driveItemJson("big-id", "big.bin", size = 5 * 1024 * 1024), HttpStatusCode.Created, jsonHeaders())
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("big-id", "big.bin", size = 5 * 1024 * 1024), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-big", ".bin")
            Files.write(local, ByteArray(5 * 1024 * 1024) { 0 }) // >4 MiB → chunked path

            provider.upload(local, "/big.bin", existingRemoteId = null)

            assertTrue(sessionBody != null, "createUploadSession must be called for a >4 MiB file")
            assertTrue(
                sessionBody!!.contains("\"@microsoft.graph.conflictBehavior\":\"fail\""),
                "DATA LOSS: a chunked create must open its session with fail, not replace; body was $sessionBody",
            )
            provider.close()
            Files.deleteIfExists(local)
        }
}
