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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #291: OneDrive lost-update — a MODIFIED file ≤4 MiB replaced via the simple content PUT
 * carried NO `If-Match` header, so a concurrent remote edit landing between the engine's
 * plan and the apply was blind-overwritten. #113 only guarded the >4 MiB upload-session
 * path; the simple PUT (the majority of files) had no optimistic-concurrency token at all.
 *
 * Each test names one orthogonal invariant:
 *  1. a ≤4 MiB MODIFIED upload sends `If-Match` with the expected eTag (the closed gap).
 *  2. a 412 Precondition Failed from the simple PUT maps to the keep-both collision outcome —
 *     NOT a silent overwrite and NOT an unhandled throw (the no-lost-update guarantee).
 *  3. a NEW upload (existingRemoteId == null) sends NO `If-Match` and still succeeds.
 *  4. a normal MODIFIED upload with no concurrent edit still succeeds.
 */
class OneDriveIfMatchTest {
    private fun driveItemJson(
        id: String,
        name: String,
        size: Long = 5,
        eTag: String? = null,
    ): String {
        val eTagField = if (eTag != null) ""","eTag":"$eTag"""" else ""
        return """
            {"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},
             "parentReference":{"path":"/drive/root:"}$eTagField}
            """.trimIndent()
    }

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
    fun `small modified upload sends If-Match with the current eTag`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        // At-upload-time eTag fetch for the MODIFIED path.
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-current"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-ifmatch", ".txt")
            Files.write(local, "world".toByteArray()) // ≤4 MiB → simple PUT

            provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(
                listOf<String?>("etag-current"),
                ifMatchHeaders,
                "LOST UPDATE: a ≤4 MiB modified upload must send If-Match with the current eTag",
            )
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `small modified upload 412 keeps both and never overwrites`() =
        runTest {
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-stale"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            val cb = request.url.parameters["@microsoft.graph.conflictBehavior"]
                            conflictBehaviors += cb
                            // The replace carrying the now-stale If-Match → server has moved on.
                            if (cb == "replace") {
                                respond(
                                    content = """{"error":{"code":"preconditionFailed","message":"etag mismatch"}}""",
                                    status = HttpStatusCode.PreconditionFailed,
                                    headers = jsonHeaders(),
                                )
                            } else {
                                // keep-both rename landed.
                                respond(driveItemJson("kept-both-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                            }
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("kept-both-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-412", ".txt")
            Files.write(local, "world".toByteArray())

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(
                listOf<String?>("replace", "rename"),
                conflictBehaviors,
                "a 412 on the guarded replace must fall back to keep-both via rename, preserving the concurrent remote edit",
            )
            assertEquals("doc 1.txt", result.name, "result reflects the kept-both copy, not an overwrite of the concurrent edit")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `new upload sends no If-Match and succeeds`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-new", ".txt")
            Files.write(local, "data".toByteArray())

            val result = provider.upload(local, "/fresh.txt", existingRemoteId = null)

            assertEquals(1, ifMatchHeaders.size, "exactly one content PUT for a fresh create")
            assertNull(ifMatchHeaders.single(), "a NEW upload must not send If-Match — there is nothing to guard against")
            assertEquals("fresh.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `small modified upload with no concurrent edit succeeds`() =
        runTest {
            var putSucceeded = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-current"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            putSucceeded = true
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-noedit", ".txt")
            Files.write(local, "world".toByteArray())

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertTrue(putSucceeded, "the guarded replace must complete when the eTag still matches")
            assertEquals("doc.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `explicit plan-time eTag is preferred over the at-upload-time fetch`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            var metadataFetched = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") -> {
                            metadataFetched = true
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-at-upload"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-plantime", ".txt")
            Files.write(local, "world".toByteArray())

            provider.upload(local, "/doc.txt", existingRemoteId = "owned-id", ifMatchETag = "etag-plan-time")

            assertEquals(
                listOf<String?>("etag-plan-time"),
                ifMatchHeaders,
                "an engine-supplied plan-time eTag must be used verbatim, not overridden by an at-upload fetch",
            )
            assertTrue(!metadataFetched, "when the caller supplies a plan-time eTag the provider must skip the at-upload metadata GET")
            provider.close()
            Files.deleteIfExists(local)
        }
}
