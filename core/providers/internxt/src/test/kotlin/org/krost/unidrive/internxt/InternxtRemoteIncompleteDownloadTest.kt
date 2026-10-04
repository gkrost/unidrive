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
import org.krost.unidrive.RemoteIncompleteDownloadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #536: a stored object shorter than the size the drive reports (a multipart upload another client
 * truncated; live evidence: a 344 MiB object whose last four 15 MiB parts never landed) fails every
 * download. The failure must be the typed permanent one the engine quarantines on — raised BEFORE any
 * byte is streamed when the shard's Content-Length already says the object is short (the live shape),
 * and after streaming with the destination removed when it does not — so no truncated cache copy is
 * ever left behind and no retry ladder burns attempts on an object that cannot succeed.
 */
class InternxtRemoteIncompleteDownloadTest {
    private val fileUuid = "file-uuid-1"
    private val fileId = "bridge-file-id-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    // BridgeFileInfo.index is a 64-char hex encryption index (#333 now validates it).
    private val index64 = "11".repeat(32)
    private val shardUrl1 = "https://shard-host.invalid/get/shard-0"

    private var declaredSize = "0"
    private var shardBody: ByteArray = "0123456789A".toByteArray()
    private var shardContentLength: String? = null
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
        """{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"$declaredSize","status":"EXISTS"}"""

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
            host == "shard-host.invalid" && request.method == HttpMethod.Get -> {
                val headers = buildList<Pair<String, List<String>>> {
                    add(HttpHeaders.ContentType to listOf("application/octet-stream"))
                    shardContentLength?.let { add(HttpHeaders.ContentLength to listOf(it)) }
                }.toTypedArray()
                respond(shardBody, HttpStatusCode.OK, headersOf(*headers))
            }
            else -> respond("""{"error":"unexpected: ${request.method} $host$path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    // The live #536 shape: the shard response's Content-Length (298,844,160) is smaller than the
    // declared drive size (360,951,317) — the refusal must come before a single byte is read.
    @Test
    fun `a stored object shorter than the declared size fails before streaming`() {
        declaredSize = "23"
        shardContentLength = "11"
        bridgeInfoJson = bridgeInfoJsonWith(listOf(shardUrl1))
        val provider = newProvider()

        val e =
            assertFailsWith<RemoteIncompleteDownloadException> {
                kotlinx.coroutines.test.runTest {
                    provider.downloadById(fileUuid, "/truncated.7z", destination)
                }
            }

        assertEquals(11L, e.storedBytes, "the stored object's Content-Length")
        assertEquals(23L, e.declaredBytes, "the size the drive reports")
        // Being a PermanentDownloadFailureException (the base type the engine's quarantine sites
        // catch) is a compile-time property of the type hierarchy; the engine's own tests pin it.
        assertEquals(
            1,
            requestLog.count { it.contains("shard-host.invalid") },
            "exactly one shard request, no retry: ${requestLog}",
        )
        assertTrue(Files.notExists(destination), "no truncated copy may reach the destination")
        assertTrue(
            Files.notExists(destination.resolveSibling("out.bin.unidrive-tmp")),
            "no temp file either",
        )
    }

    // Second line: without a trustworthy Content-Length the short object is only known after the
    // stream ends — the moved partial copy must then be removed and the failure stay typed.
    @Test
    fun `a short stream without a trustworthy length fails after streaming and leaves no file`() {
        declaredSize = "23"
        shardContentLength = null
        bridgeInfoJson = bridgeInfoJsonWith(listOf(shardUrl1))
        val provider = newProvider()

        val e =
            assertFailsWith<RemoteIncompleteDownloadException> {
                kotlinx.coroutines.test.runTest {
                    provider.downloadById(fileUuid, "/truncated.7z", destination)
                }
            }

        assertEquals(11L, e.storedBytes)
        assertEquals(23L, e.declaredBytes)
        assertEquals(
            1,
            requestLog.count { it.contains("shard-host.invalid") },
            "exactly one shard request, no retry: ${requestLog}",
        )
        assertTrue(Files.notExists(destination), "the moved partial copy must be removed on failure")
        assertTrue(
            Files.notExists(destination.resolveSibling("out.bin.unidrive-tmp")),
            "no temp file either",
        )
    }
}
