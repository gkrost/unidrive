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
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #402: Internxt path-to-id resolution must fail closed on ambiguity. Where two
 * live items share a name, `getMetadata` / `resolveFolder` used to take the first
 * listing match — a delete or move resolved through them could hit the wrong twin.
 * They must throw instead, and the SPI by-id entry points (deleteById / moveById)
 * must address the requested uuid, never a same-named sibling.
 */
class InternxtAmbiguousPathTest {
    private val rootUuid = "root-folder-uuid"

    // Mock state, set per test.
    private var folderChildrenJson = "[]"
    private var folderFilesJson = "[]"
    private val fileMetaByUuid = mutableMapOf<String, String>() // uuid → JSON
    private val folderMetaByUuid = mutableMapOf<String, String>()
    private val fileMeta404 = mutableSetOf<String>()
    private val folderMeta404 = mutableSetOf<String>()
    private val requestLog = mutableListOf<String>()
    private var trashBody: String? = null

    private fun fileJson(
        uuid: String,
        plainName: String,
        type: String = "txt",
    ) = """{"uuid":"$uuid","plainName":"$plainName","type":"$type","size":"7","status":"EXISTS"}"""

    private fun folderJson(
        uuid: String,
        plainName: String,
    ) = """{"uuid":"$uuid","plainName":"$plainName","status":"EXISTS"}"""

    private fun newProvider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = java.nio.file.Files.createTempFile("ud-amb-token", ".tmp")))
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
                rootFolderId = rootUuid,
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
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

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val method = request.method
        requestLog += "$method $path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/folders/content/$rootUuid" && method == HttpMethod.Get ->
                respond(
                    """{"children":$folderChildrenJson,"files":$folderFilesJson}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            path.startsWith("/drive/files/") && path.endsWith("/meta") && method == HttpMethod.Get -> {
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                when {
                    uuid in fileMeta404 -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond(fileMetaByUuid.getValue(uuid), HttpStatusCode.OK, jsonHeaders)
                }
            }
            path.startsWith("/drive/folders/") && path.endsWith("/meta") && method == HttpMethod.Get -> {
                val uuid = path.removePrefix("/drive/folders/").removeSuffix("/meta")
                when {
                    uuid in folderMeta404 -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond(folderMetaByUuid.getValue(uuid), HttpStatusCode.OK, jsonHeaders)
                }
            }
            path == "/drive/storage/trash/add" && method == HttpMethod.Post -> {
                trashBody = drainBody(request.body)
                respond("""{"items":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/files/") && (method == HttpMethod.Patch || method == HttpMethod.Put) -> {
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                respond(fileJson(uuid, plainName = "moved", type = "txt"), HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/folders/") && method == HttpMethod.Patch -> {
                val uuid = path.removePrefix("/drive/folders/")
                respond(folderJson(uuid, plainName = "moved"), HttpStatusCode.OK, jsonHeaders)
            }
            else -> respond("""{"error":"unexpected: $method $path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun drainBody(content: OutgoingContent): String =
        when (content) {
            is TextContent -> content.text
            is ByteArrayContent -> content.bytes().decodeToString()
            else -> error("unexpected body type: ${content.javaClass}")
        }

    // ---- fail-closed resolution ----

    @Test
    fun `getMetadata with two same-named files throws instead of picking one`() =
        runTest {
            folderFilesJson =
                "[${fileJson("twin-a", "dup")},${fileJson("twin-b", "dup")}]"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.getMetadata("/dup.txt") }
            assertTrue("ambiguous" in (e.message ?: ""), "message must say ambiguous; got: ${e.message}")
            assertTrue("2" in (e.message ?: ""))
        }

    @Test
    fun `getMetadata with two same-named child folders throws instead of picking one`() =
        runTest {
            folderChildrenJson =
                "[${folderJson("folder-a", "dup")},${folderJson("folder-b", "dup")}]"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.getMetadata("/dup") }
            assertTrue("ambiguous" in (e.message ?: ""))
        }

    @Test
    fun `resolving THROUGH an ambiguous folder fails closed`() =
        runTest {
            // /dup exists twice at the root; a file under either is unaddressable by path.
            folderChildrenJson =
                "[${folderJson("folder-a", "dup")},${folderJson("folder-b", "dup")}]"
            val provider = newProvider()
            assertFailsWith<ProviderException> { provider.getMetadata("/dup/x.txt") }
        }

    @Test
    fun `a unique name still resolves`() =
        runTest {
            folderFilesJson = "[${fileJson("solo-uuid", "unique")}]"
            val provider = newProvider()
            val item = provider.getMetadata("/unique.txt")
            assertEquals("solo-uuid", item.id)
        }

    // ---- by-id operations ----

    @Test
    fun `deleteById trashes the file uuid without resolving by path`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            val provider = newProvider()
            provider.deleteById("twin-a", "/dup.txt")
            val body = trashBody
            assertTrue(body != null && "twin-a" in body && """"file"""" in body, "trash body: $body")
            assertTrue("twin-b" !in (body ?: ""), "the sibling twin must be untouched")
        }

    @Test
    fun `deleteById falls back to the folder probe for a folder uuid`() =
        runTest {
            fileMeta404 += "folder-a" // not a file
            folderMetaByUuid["folder-a"] = folderJson("folder-a", "dup")
            val provider = newProvider()
            provider.deleteById("folder-a", "/dup")
            val body = trashBody
            assertTrue(body != null && "folder-a" in body && """"folder""" in body, "trash body: $body")
        }

    @Test
    fun `deleteById on an unknown uuid surfaces not-found`() =
        runTest {
            fileMeta404 += "gone-uuid"
            folderMeta404 += "gone-uuid"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.deleteById("gone-uuid", "/gone.txt") }
            assertTrue("not found" in (e.message ?: "").lowercase())
        }

    @Test
    fun `moveById patches the requested uuid, never a same-named sibling`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            fileMetaByUuid["twin-b"] = fileJson("twin-b", "dup")
            val provider = newProvider()
            val moved = provider.moveById("twin-a", "/dup.txt", "/moved.txt")
            assertEquals("twin-a", moved.id)
            val patch =
                requestLog.firstOrNull { it.startsWith("PATCH /drive/files/twin-a") }
                    ?: requestLog.firstOrNull { it.startsWith("PUT /drive/files/twin-a") }
            assertTrue(patch != null, "expected a write to twin-a's endpoint; requests: $requestLog")
            assertTrue(
                requestLog.none { it.contains("twin-b") },
                "the sibling twin must not be touched; requests: $requestLog",
            )
        }
}
