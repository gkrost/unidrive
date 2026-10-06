package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #493: an upload the drive server refuses as a request (400, 413, 415, 422) is refused again with the same bytes; it is
 * reported as a [PermanentUploadFailureException], so the hydration queue does not run its retry ladder on it. A server
 * error keeps its own (retryable) type.
 */
class InternxtUploadRefusalTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val shardUrl = "https://shard-host.invalid/refusal-put"
    private val tmp: Path = Files.createTempDirectory("ud-refusal-")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun newProvider(createStatus: HttpStatusCode): InternxtProvider =
        newProviderWith(createStatus)

    /**
     * The folder-listing route answers with an EMPTY children list: the walk
     * succeeds and proves the target segment absent — positive proof (a trashed
     * parent, #580), not a degraded gateway (that fails the listing itself).
     */
    private fun newProviderParentGone(): InternxtProvider =
        newProviderWith(HttpStatusCode.OK, emptyListing = true)

    private fun newProviderWith(
        createStatus: HttpStatusCode,
        emptyListing: Boolean = false,
    ): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tmp))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = bucket,
            ),
        )
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpField.isAccessible = true
        (httpField.get(api) as? HttpClient)?.close()
        val json = headersOf("Content-Type", "application/json")
        httpField.set(
            api,
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        emptyListing && url.contains("/folders/content/") ->
                            respond("""{"children":[],"files":[]}""", HttpStatusCode.OK, json)
                        url.contains("/v2/buckets/") && url.contains("/files/start") ->
                            respond("""{"uploads":[{"index":0,"uuid":"shard-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                        url == shardUrl -> respond("", HttpStatusCode.OK)
                        url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                            respond("""{"id":"bucket-entry-1","index":"${"aa".repeat(32)}","bucket":"$bucket","name":"enc"}""", HttpStatusCode.OK, json)
                        request.url.encodedPath == "/drive/files" && request.method == HttpMethod.Post ->
                            respond("""{"statusCode":${createStatus.value},"message":["refused"],"error":"x"}""", createStatus, json)
                        else -> respond("""{"error":"unexpected ${request.method.value} $url"}""", HttpStatusCode.NotFound, json)
                    }
                },
            ),
        )
        return provider
    }

    private fun local(): Path = tmp.resolve("f.txt").also { Files.write(it, ByteArray(10) { 0x41 }) }

    @Test
    fun `a 400 from the drive is a permanent upload failure`() =
        runTest {
            val provider = newProvider(HttpStatusCode.BadRequest)
            try {
                val e = assertFailsWith<PermanentUploadFailureException> {
                    provider.upload(local(), "/f.txt", existingRemoteId = null, onProgress = null)
                }
                assertTrue("400" in (e.message ?: ""), "the status is named: ${e.message}")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a server error keeps its retryable type`() =
        runTest {
            val provider = newProvider(HttpStatusCode.InternalServerError)
            try {
                val e = assertFailsWith<InternxtApiException> {
                    provider.upload(local(), "/f.txt", existingRemoteId = null, onProgress = null)
                }
                assertEquals(500, e.statusCode, "a server error is not turned into a permanent failure")
            } finally {
                provider.close()
            }
        }

    // #580: the parent folder was trashed in the cloud; the walk's listing succeeds and
    // the target segment is not in it. That is permanent for the current state — the same
    // call with the same bytes fails again forever — so it is a PermanentUploadFailureException
    // (no retry ladder, replay and same-bytes resubmission skip it, the cache bytes stay),
    // NOT the plain retryable ProviderException the walk used to raise.
    @Test
    fun `an upload whose parent the walk proves gone is a permanent refusal`() =
        runTest {
            val provider = newProviderParentGone()
            try {
                val e =
                    assertFailsWith<PermanentUploadFailureException> {
                        provider.upload(local(), "/_ud-test-0510/new.bin", existingRemoteId = null, onProgress = null)
                    }
                assertTrue(
                    "Folder not found" in (e.message ?: ""),
                    "the walk's proof of absence is carried in the message: ${e.message}",
                )
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an empty-file upload into a parent the walk proves gone is also a permanent refusal`() =
        runTest {
            val provider = newProviderParentGone()
            try {
                val e =
                    assertFailsWith<PermanentUploadFailureException> {
                        provider.upload(local().let { Files.write(it, ByteArray(0)); it }, "/_ud-test-0510/zero.bin", existingRemoteId = null, onProgress = null)
                    }
                assertTrue("Folder not found" in (e.message ?: ""), e.message)
            } finally {
                provider.close()
            }
        }
}
