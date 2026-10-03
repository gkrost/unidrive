package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import org.krost.unidrive.PermanentDownloadFailureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #335: multi-shard files used to download only the first shard; the decrypted
 * truncation then tripped the length guard as a RETRIABLE error, so the engine
 * burned retries forever instead of quarantining. More than one usable shard
 * must fail as a permanent (quarantining) download failure before any download
 * request is made; a single shard keeps working.
 */
class InternxtMultiShardDownloadTest {
    private val fileUuid = "file-uuid-1"
    private val fileId = "bridge-file-id-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    // BridgeFileInfo.index is a 64-char hex encryption index (#333 now validates it).
    private val index64 = "11".repeat(32)
    private val shardUrl1 = "https://shard-host.invalid/get/shard-0"
    private val shardUrl2 = "https://shard-host.invalid/get/shard-1"

    private var bridgeInfoJson = ""
    private val requestLog = mutableListOf<String>()
    private lateinit var destination: Path

    private fun newProvider(): InternxtProvider {
        destination = Files.createTempDirectory("ud-shard-dl").resolve("out.bin")
        val provider = InternxtProvider(InternxtConfig(tokenPath = Files.createTempFile("ud-shard-token", ".tmp")))
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
        val httpClientField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(api) as? HttpClient)?.close()
        httpClientField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun fileMetaJson() =
        """{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"0","status":"EXISTS"}"""

    private fun bridgeInfoJsonWith(shardUrls: List<String>): String =
        buildString {
            append("""{"bucket":"$bucket","id":"$fileId","index":"$index64","shards":[""")
            append(shardUrls.joinToString(",") { url -> """{"index":0,"hash":"h","url":"$url"}""" })
            append("]}")
        }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val host = request.url.host
        requestLog += "${request.method} $host$path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get ->
                respond(fileMetaJson(), HttpStatusCode.OK, jsonHeaders)
            path == "/buckets/$bucket/files/$fileId/info" && request.method == HttpMethod.Get ->
                respond(bridgeInfoJson, HttpStatusCode.OK, jsonHeaders)
            host == "shard-host.invalid" && request.method == HttpMethod.Get ->
                respond(
                    "0123456789A".toByteArray(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                )
            else -> respond("""{"error":"unexpected: ${request.method} $host$path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    @Test
    fun `multi-shard bridge info fails permanently without downloading`() {
        bridgeInfoJson = bridgeInfoJsonWith(listOf(shardUrl1, shardUrl2))
        val provider = newProvider()

        val e =
            assertFailsWith<PermanentDownloadFailureException> {
                kotlinx.coroutines.test.runTest {
                    provider.downloadById(fileUuid, "/multi.bin", destination)
                }
            }

        assertTrue(
            "multi-shard Internxt files are not supported (2 shards)" in (e.message ?: ""),
            "message must name the multi-shard limit; got: ${e.message}",
        )
        assertTrue(
            requestLog.none { it.contains("shard-host.invalid") },
            "no download request may be made; requests: $requestLog",
        )
    }

    @Test
    fun `single-shard bridge info still downloads`() =
        kotlinx.coroutines.test.runTest {
            bridgeInfoJson = bridgeInfoJsonWith(listOf(shardUrl1))
            val provider = newProvider()

            val written = provider.downloadById(fileUuid, "/single.bin", destination)

            assertEquals(11L, written, "the mocked shard serves 11 bytes")
            assertTrue(Files.exists(destination), "the destination file must exist")
            assertTrue(requestLog.any { it.contains("shard-host.invalid") }, "the shard was fetched")
        }

    @Test
    fun `a blank shard URL among several usable ones still counts as multi-shard`() {
        // Two usable (non-blank) URLs plus one blank entry: the blank one is not a
        // second shard, but the other two are — still unsupported, still permanent.
        bridgeInfoJson = bridgeInfoJsonWith(listOf(shardUrl1, "", shardUrl2))
        val provider = newProvider()

        val e =
            assertFailsWith<PermanentDownloadFailureException> {
                kotlinx.coroutines.test.runTest {
                    provider.downloadById(fileUuid, "/mixed.bin", destination)
                }
            }
        assertTrue("multi-shard Internxt files are not supported (2 shards)" in (e.message ?: ""))
    }
}
