package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A shared-with-me item lives in another user's drive: every call that takes its id must address it as
 * `/drives/{remoteItem.parentReference.driveId}/items/{remoteItem.id}`, never as `/me/drive/items/{id}`.
 * Driven by the two response shapes of `/me/drive/sharedWithMe` (Consumer and Business, fixtures under
 * `resources/graph`) and a mock transport; no account, no network.
 */
class SharedWithMeAddressingTest {
    private data class Shape(
        val name: String,
        val fixture: String,
        val fileDriveId: String,
        val fileItemId: String,
        val fileSize: Long,
        val fileHash: String,
        val folderDriveId: String,
        val folderItemId: String,
    )

    private val shapes =
        listOf(
            Shape(
                "consumer",
                "/graph/sharedwithme-consumer.json",
                "c0ffee0123456789",
                "C0FFEE0123456789!1042",
                52428800,
                "cXVpY2t4b3JoYXNoMTIzNDU=",
                "d00d00d00d00d00d",
                "D00D00D00D00D00D!77",
            ),
            Shape(
                "business",
                "/graph/sharedwithme-business.json",
                "b!Tq3m-8XkZr9PLwq1Ef2g_dHs0aBcDeFgHiJkLmNoPqRsTuVwXyZ012345678",
                "01Z6X7Y8W9V0U1T2S3R4Q5P6O7N8M9L0",
                83011,
                "QnVzaW5lc3NRdWlja1hvcjEyMw==",
                "b!Zk9_3pQrStUvWx-YzAbCdEfGhIjKlMnOpQrStUvWxYz987654321",
                "01A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5",
            ),
        )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val tempDirs = mutableListOf<Path>()
    private val providers = mutableListOf<OneDriveProvider>()

    @AfterTest
    fun cleanUp() {
        providers.forEach { it.close() }
        tempDirs.forEach { dir -> runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() } } } }
    }

    private class Recorded(
        val method: HttpMethod,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
    ) {
        override fun toString() = "$method $path"
    }

    private fun decodedPath(request: HttpRequestData): String = URLDecoder.decode(request.url.encodedPath, Charsets.UTF_8)

    private fun bodyOf(request: HttpRequestData): String =
        when (val content = request.body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().toString(Charsets.UTF_8)
            else -> ""
        }

    private fun fixture(resource: String): String = javaClass.getResource(resource)!!.readText()

    private fun tempDir(): Path = Files.createTempDirectory("ud-shared-addressing").also { tempDirs.add(it) }

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val config = OneDriveConfig(tokenPath = tempDir(), includeShared = true)
        val provider = OneDriveProvider(config)
        val service = GraphApiService(config = config, tokenProvider = { _ -> "test-token" })
        val httpClient = GraphApiService::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        (httpClient.get(service) as? HttpClient)?.close()
        httpClient.set(service, HttpClient(engine))
        val graphApi = OneDriveProvider::class.java.getDeclaredField("graphApi").apply { isAccessible = true }
        (graphApi.get(provider) as? GraphApiService)?.close()
        graphApi.set(provider, service)
        providers += provider
        return provider
    }

    /** The shared items [shape] lists, by display name, as the engine receives them. */
    private suspend fun listedBy(
        shape: Shape,
        requests: MutableList<Recorded> = mutableListOf(),
    ): Map<String, CloudItem> {
        val engine =
            MockEngine { request ->
                val path = decodedPath(request)
                requests += Recorded(request.method, path, "", emptyMap())
                when {
                    path.endsWith("/sharedWithMe") -> respond(fixture(shape.fixture), HttpStatusCode.OK, jsonHeaders)
                    else -> respond("""{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}""", HttpStatusCode.OK, jsonHeaders)
                }
            }
        val provider = providerWith(engine)
        val page = assertIs<CapabilityResult.Success<DeltaPage>>(provider.deltaWithShared(null)).value
        return page.items.associateBy { it.name }
    }

    private fun fileOf(items: Map<String, CloudItem>, shape: Shape): CloudItem =
        assertNotNull(items.values.firstOrNull { !it.isFolder }, "${shape.name}: the shared file is listed")

    private fun folderOf(items: Map<String, CloudItem>, shape: Shape): CloudItem =
        assertNotNull(items.values.firstOrNull { it.isFolder }, "${shape.name}: the shared folder is listed")

    @Test
    fun `the id of a shared item carries the remote drive and item id in both response shapes`() =
        runTest {
            for (shape in shapes) {
                val items = listedBy(shape)
                val file = fileOf(items, shape)
                val folder = folderOf(items, shape)

                assertTrue(file.id.startsWith("shared:"), "${shape.name}: ${file.id}")
                assertTrue(file.id.contains(shape.fileDriveId) && file.id.contains(shape.fileItemId), "${shape.name} file id: ${file.id}")
                assertTrue(folder.id.contains(shape.folderDriveId) && folder.id.contains(shape.folderItemId), "${shape.name} folder id: ${folder.id}")
                assertEquals(2, items.size, "${shape.name}: both entries are listed")
                assertEquals(shape.fileSize, file.size, "${shape.name}: size comes from the entry or its remoteItem")
                assertEquals(shape.fileHash, file.hash, "${shape.name}: the hash is on remoteItem")
                assertEquals("/Shared/${file.name}", file.path)
            }
        }

    @Test
    fun `download of a shared item is addressed in the remote drive and never in the own drive`() =
        runTest {
            for (shape in shapes) {
                val id = fileOf(listedBy(shape), shape).id
                val requests = mutableListOf<Recorded>()
                val engine =
                    MockEngine { request ->
                        val path = decodedPath(request)
                        requests += Recorded(request.method, path, "", emptyMap())
                        when {
                            path == "/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}" ->
                                respond(
                                    """{"id":"${shape.fileItemId}","name":"f.bin","size":5,"file":{"mimeType":"application/octet-stream"},""" +
                                        """"@microsoft.graph.downloadUrl":"https://cdn.example/dl"}""",
                                    HttpStatusCode.OK,
                                    jsonHeaders,
                                )
                            request.url.host == "cdn.example" ->
                                respond("hello", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
                            else -> respond("""{"error":{"code":"itemNotFound"}}""", HttpStatusCode.NotFound, jsonHeaders)
                        }
                    }
                val provider = providerWith(engine)
                val dest = tempDir().resolve("out.bin")

                val bytes = provider.downloadById(id, "/Shared/f.bin", dest)

                assertEquals(5, bytes, shape.name)
                assertEquals("hello", Files.readString(dest), shape.name)
                assertTrue(requests.none { it.path.contains("/me/drive") }, "${shape.name}: requests were $requests")
            }
        }

    @Test
    fun `download falls back to the content endpoint of the remote drive`() =
        runTest {
            val shape = shapes[1]
            val id = fileOf(listedBy(shape), shape).id
            val requests = mutableListOf<Recorded>()
            val engine =
                MockEngine { request ->
                    val path = decodedPath(request)
                    requests += Recorded(request.method, path, "", emptyMap())
                    when (path) {
                        "/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}" ->
                            respond("""{"id":"${shape.fileItemId}","name":"f.bin","size":5,"file":{}}""", HttpStatusCode.OK, jsonHeaders)
                        "/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}/content" ->
                            respond("hello", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5"))
                        else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                    }
                }
            val provider = providerWith(engine)
            val dest = tempDir().resolve("out.bin")

            provider.downloadById(id, "/Shared/f.bin", dest)

            assertEquals("hello", Files.readString(dest))
            assertTrue(requests.any { it.path.endsWith("/content") }, "requests were $requests")
            assertTrue(requests.none { it.path.contains("/me/drive") }, "requests were $requests")
        }

    @Test
    fun `download of a shared item that is gone is a permanent failure`() =
        runTest {
            val shape = shapes[0]
            val id = fileOf(listedBy(shape), shape).id
            val engine =
                MockEngine { respond("""{"error":{"code":"itemNotFound","message":"gone"}}""", HttpStatusCode.NotFound, jsonHeaders) }
            val provider = providerWith(engine)

            assertFailsWith<PermanentDownloadFailureException> {
                provider.downloadById(id, "/Shared/f.bin", tempDir().resolve("out.bin"))
            }
        }

    @Test
    fun `delete by id of a shared item is addressed in the remote drive`() =
        runTest {
            for (shape in shapes) {
                val id = fileOf(listedBy(shape), shape).id
                val requests = mutableListOf<Recorded>()
                val engine =
                    MockEngine { request ->
                        requests += Recorded(request.method, decodedPath(request), "", emptyMap())
                        respond("", HttpStatusCode.NoContent)
                    }
                val provider = providerWith(engine)

                provider.deleteById(id, "/Shared/f.bin")

                assertEquals(listOf("DELETE /v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}"), requests.map { it.toString() }, shape.name)
            }
        }

    @Test
    fun `verifyItemExists reads a shared item from the remote drive`() =
        runTest {
            for (shape in shapes) {
                val id = fileOf(listedBy(shape), shape).id
                val seen = mutableListOf<String>()
                var status = HttpStatusCode.OK
                val engine =
                    MockEngine { request ->
                        seen += decodedPath(request)
                        respond("""{"id":"${shape.fileItemId}","name":"f.bin","size":5}""", status, jsonHeaders)
                    }
                val provider = providerWith(engine)

                assertEquals(CapabilityResult.Success(true), provider.verifyItemExists(id), shape.name)
                status = HttpStatusCode.NotFound
                assertEquals(CapabilityResult.Success(false), provider.verifyItemExists(id), shape.name)

                assertEquals(List(2) { "/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}" }, seen, shape.name)
            }
        }

    @Test
    fun `a shared item is renamed in place by id and keeps its shared id`() =
        runTest {
            for (shape in shapes) {
                val id = fileOf(listedBy(shape), shape).id
                val requests = mutableListOf<Recorded>()
                val engine =
                    MockEngine { request ->
                        requests += Recorded(request.method, decodedPath(request), bodyOf(request), emptyMap())
                        respond(
                            """{"id":"${shape.fileItemId}","name":"renamed.bin","size":5,"eTag":"e1","file":{}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                val provider = providerWith(engine)

                val moved = provider.moveById(id, "/Shared/f.bin", "/Shared/renamed.bin")

                val patch = requests.single { it.method.value == "PATCH" }
                assertEquals("/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}", patch.path, shape.name)
                assertTrue(patch.body.contains("\"name\":\"renamed.bin\""), patch.body)
                assertFalse(patch.body.contains("parentReference"), "a rename must not re-parent: ${patch.body}")
                assertTrue(requests.none { it.path.contains("/me/drive") }, "${shape.name}: requests were $requests")
                assertEquals(id, moved.id, shape.name)
                assertEquals("/Shared/renamed.bin", moved.path, shape.name)
            }
        }

    @Test
    fun `a shared item is not moved out of its folder into the own drive`() =
        runTest {
            val shape = shapes[0]
            val id = fileOf(listedBy(shape), shape).id
            val requests = mutableListOf<Recorded>()
            val engine =
                MockEngine { request ->
                    requests += Recorded(request.method, decodedPath(request), "", emptyMap())
                    respond("""{"id":"x","name":"f.bin","size":5,"eTag":"e1"}""", HttpStatusCode.OK, jsonHeaders)
                }
            val provider = providerWith(engine)

            assertFailsWith<ProviderException> { provider.moveById(id, "/Shared/f.bin", "/Docs/f.bin") }

            assertTrue(requests.none { it.method.value == "PATCH" }, "requests were $requests")
        }

    @Test
    fun `a small update of a shared file replaces it in place in the remote drive and keeps the shared id`() =
        runTest {
            for (shape in shapes) {
                val id = fileOf(listedBy(shape), shape).id
                val requests = mutableListOf<Recorded>()
                val engine =
                    MockEngine { request ->
                        val path = decodedPath(request)
                        requests += Recorded(request.method, path, bodyOf(request), mapOf("If-Match" to (request.headers[HttpHeaders.IfMatch] ?: "")))
                        // The drive answers with the item's own id, not the shared one.
                        respond(
                            """{"id":"${shape.fileItemId}","name":"f.bin","size":5,"eTag":"etag-now","file":{}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                val provider = providerWith(engine)
                val local = tempDir().resolve("f.bin").also { Files.write(it, "world".toByteArray()) }

                val uploaded = provider.upload(local, "/Shared/f.bin", existingRemoteId = id)

                val itemPath = "/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}"
                val put = requests.single { it.method == HttpMethod.Put }
                assertEquals("$itemPath/content", put.path, shape.name)
                assertEquals("etag-now", put.headers["If-Match"], "${shape.name}: the update is guarded by the item's eTag")
                assertEquals(listOf(itemPath), requests.filter { it.method.value == "PATCH" }.map { it.path }, shape.name)
                assertTrue(requests.none { it.path.contains("/me/drive") }, "${shape.name}: requests were $requests")
                assertEquals(id, uploaded.id, shape.name)
                assertEquals("/Shared/f.bin", uploaded.path, shape.name)
            }
        }

    @Test
    fun `a large update of a shared file opens its upload session in the remote drive`() =
        runTest {
            val shape = shapes[1]
            val id = fileOf(listedBy(shape), shape).id
            val requests = mutableListOf<Recorded>()
            val engine =
                MockEngine { request ->
                    val path = decodedPath(request)
                    requests += Recorded(request.method, path, "", emptyMap())
                    when {
                        path.endsWith("/createUploadSession") ->
                            respond("""{"uploadUrl":"https://upload.example/session-1","expirationDateTime":"2099-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders)
                        request.url.host == "upload.example" ->
                            respond("""{"id":"${shape.fileItemId}","name":"big.bin","size":4194305,"file":{}}""", HttpStatusCode.OK, jsonHeaders)
                        else -> respond("""{"id":"${shape.fileItemId}","name":"big.bin","size":1,"eTag":"e1","file":{}}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val provider = providerWith(engine)
            val local = tempDir().resolve("big.bin").also { Files.write(it, ByteArray(4 * 1024 * 1024 + 1)) }

            val uploaded = provider.upload(local, "/Shared/big.bin", existingRemoteId = id)

            val session = requests.single { it.path.endsWith("/createUploadSession") }
            assertEquals("/v1.0/drives/${shape.fileDriveId}/items/${shape.fileItemId}/createUploadSession", session.path)
            assertTrue(requests.none { it.path.contains("/me/drive") }, "requests were $requests")
            assertEquals(id, uploaded.id)
        }

    @Test
    fun `a shared id from an earlier listing names no drive and is refused without a request`() =
        runTest {
            val requests = mutableListOf<Recorded>()
            val engine =
                MockEngine { request ->
                    requests += Recorded(request.method, decodedPath(request), "", emptyMap())
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
            val provider = providerWith(engine)

            assertFailsWith<PermanentDownloadFailureException> {
                provider.downloadById("shared:LEGACYID", "/Shared/f.bin", tempDir().resolve("out.bin"))
            }
            assertEquals(CapabilityResult.Success(false), provider.verifyItemExists("shared:LEGACYID"))
            assertTrue(requests.isEmpty(), "requests were $requests")
        }

    @Test
    fun `an entry that names no remote drive is left out of the listing`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (decodedPath(request).endsWith("/sharedWithMe")) {
                        respond(
                            """{"value":[{"id":"stub","name":"orphan.txt","size":1,"file":{}},""" +
                                """{"id":"stub2","name":"ok.txt","size":1,"remoteItem":{"id":"R1","file":{},"parentReference":{"driveId":"D1"}}}]}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        respond("""{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val provider = providerWith(engine)

            val page = assertIs<CapabilityResult.Success<DeltaPage>>(provider.deltaWithShared(null)).value

            assertEquals(listOf("ok.txt"), page.items.map { it.name })
        }

    @Test
    fun `requests to another drive are named in the request metrics`() {
        assertEquals(
            "GET /v1.0/drives/{id}/items/{id}/content",
            GraphEndpoints.classifier.classify("GET", "graph.microsoft.com", "/v1.0/drives/b!Tq3m-8Xk/items/01Z6X7/content"),
        )
    }
}
