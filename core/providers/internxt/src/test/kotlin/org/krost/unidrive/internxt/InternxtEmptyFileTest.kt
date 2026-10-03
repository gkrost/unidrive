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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #485: empty files. The drive API validates `fileId` against `size` on create (POST /files) and replace
 * (PUT /files/{uuid}) alike: required when size > 0, refused when size == 0 ("fileId must not be provided when size is 0").
 * The mock enforces exactly that and fails every bridge request, so an empty file must reach the drive with size 0, no
 * fileId and no bridge traffic; downloading one must not ask the bridge either.
 */
class InternxtEmptyFileTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private var createResponses: MutableList<HttpStatusCode> = mutableListOf()
    private var remoteFilesJson = "[]"
    private val tmp: Path = Files.createTempDirectory("ud-empty-")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun newProvider(): InternxtProvider {
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
        httpField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun bodyOf(request: HttpRequestData): JsonObject {
        val text =
            when (val b = request.body) {
                is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                is io.ktor.http.content.TextContent -> b.text
                else -> "{}"
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    // drive-server's ValidateFileIdWithSize, as the mock's contract
    private fun violatesFileIdRule(body: JsonObject): Boolean {
        val size = body["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
        val hasFileId = body.containsKey("fileId")
        return (size == 0L && hasFileId) || (size > 0L && !hasFileId)
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val url = request.url.toString()
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val bad = """{"message":["fileId must not be provided when size is 0"],"error":"Bad Request","statusCode":400}"""
        return when {
            url.contains("/v2/buckets/") || path.startsWith("/buckets/") || request.url.host.endsWith(".invalid") ->
                respond("""{"error":"no bridge call expected for an empty file: $path"}""", HttpStatusCode.InternalServerError, json)
            path == "/drive/files" && request.method == HttpMethod.Post -> {
                val body = bodyOf(request)
                bodies += body
                if (violatesFileIdRule(body)) return respond(bad, HttpStatusCode.BadRequest, json)
                val status = if (createResponses.isEmpty()) HttpStatusCode.OK else createResponses.removeAt(0)
                if (status != HttpStatusCode.OK) {
                    respond("""{"statusCode":409,"message":"File already exists","error":"Conflict"}""", status, json)
                } else {
                    val name = body["plainName"]?.jsonPrimitive?.content ?: ""
                    respond("""{"uuid":"new-uuid","plainName":"$name","type":"txt","size":"0","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
                }
            }
            path.startsWith("/drive/files/") && path.endsWith("/meta") && request.method == HttpMethod.Get ->
                respond("""{"uuid":"empty-uuid","plainName":"e","type":"txt","size":"0","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
            path.startsWith("/drive/files/") && request.method == HttpMethod.Put -> {
                val body = bodyOf(request)
                bodies += body
                if (violatesFileIdRule(body)) return respond(bad, HttpStatusCode.BadRequest, json)
                respond("""{"uuid":"existing-uuid","plainName":"e","type":"txt","size":"0","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
            }
            path.startsWith("/drive/folders/content/") ->
                respond("""{"children":[],"files":$remoteFilesJson}""", HttpStatusCode.OK, json)
            else -> respond("""{"error":"unexpected ${request.method.value} $path"}""", HttpStatusCode.NotFound, json)
        }
    }

    private fun emptyLocal(name: String): Path = tmp.resolve(name).also { Files.write(it, ByteArray(0)) }

    private fun noBridgeTraffic() =
        assertTrue(requests.none { it.contains("/buckets/") || it.contains("shard") }, "no bridge request expected: $requests")

    @Test
    fun `a new empty file is created with size 0 and no fileId, without the bridge`() =
        runTest {
            val provider = newProvider()
            try {
                val item = provider.upload(emptyLocal("e.txt"), "/e.txt", existingRemoteId = null, onProgress = null)
                assertEquals("new-uuid", item.id)
                val body = bodies.single()
                assertEquals("0", body["size"]?.jsonPrimitive?.content)
                assertFalse(body.containsKey("fileId"), "fileId must not be sent for an empty file: $body")
                noBridgeTraffic()
            } finally {
                provider.close()
            }
        }

    @Test
    fun `replacing a file with empty content sends size 0 and no fileId`() =
        runTest {
            val provider = newProvider()
            try {
                provider.upload(emptyLocal("e.txt"), "/e.txt", existingRemoteId = "existing-uuid", onProgress = null)
                val put = bodies.single()
                assertEquals("0", put["size"]?.jsonPrimitive?.content)
                assertFalse(put.containsKey("fileId"), "fileId must not be sent for an empty replacement: $put")
                assertTrue(requests.any { it == "PUT /drive/files/existing-uuid" }, "the replace goes to the existing uuid: $requests")
                noBridgeTraffic()
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a create collision with an empty remote adopts it`() =
        runTest {
            createResponses = mutableListOf(HttpStatusCode.Conflict)
            remoteFilesJson = """[{"uuid":"remote-empty","plainName":"e","type":"txt","size":"0","bucket":"$bucket","folderUuid":"root-folder-uuid","status":"EXISTS"}]"""
            val provider = newProvider()
            try {
                val item = provider.upload(emptyLocal("e.txt"), "/e.txt", existingRemoteId = null, onProgress = null)
                assertEquals("remote-empty", item.id)
                assertEquals(1, bodies.size, "no conflict copy for an identical (empty) remote")
                noBridgeTraffic()
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a create collision with a non-empty remote keeps it and adds an empty conflict copy`() =
        runTest {
            createResponses = mutableListOf(HttpStatusCode.Conflict)
            remoteFilesJson = """[{"uuid":"remote-full","fileId":"abc","plainName":"e","type":"txt","size":"42","bucket":"$bucket","folderUuid":"root-folder-uuid","status":"EXISTS"}]"""
            val provider = newProvider()
            try {
                provider.upload(emptyLocal("e.txt"), "/e.txt", existingRemoteId = null, onProgress = null)
                assertEquals(2, bodies.size)
                val copy = bodies[1]
                assertTrue(copy["plainName"]!!.jsonPrimitive.content.contains("conflict"), "conflict copy name: $copy")
                assertFalse(copy.containsKey("fileId"))
                assertTrue(requests.none { it.startsWith("PUT ") }, "the non-empty remote must not be replaced: $requests")
                noBridgeTraffic()
            } finally {
                provider.close()
            }
        }

    @Test
    fun `downloading an empty file writes zero bytes without asking the bridge`() =
        runTest {
            val provider = newProvider()
            try {
                val destination = tmp.resolve("out").resolve("e.txt")
                val written = provider.downloadById("empty-uuid", "/e.txt", destination)
                assertEquals(0L, written)
                assertTrue(Files.exists(destination))
                assertEquals(0L, Files.size(destination))
                noBridgeTraffic()
            } finally {
                provider.close()
            }
        }
}
