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
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InternxtLiveChildrenTest {
    @Test
    fun `live folder listing matches sync live-item predicate`() {
        val content =
            FolderContentResponse(
                children =
                    listOf(
                        InternxtFolder(uuid = "live-folder", plainName = "live-folder"),
                        InternxtFolder(uuid = "trashed-folder", plainName = "trashed-folder", status = "TRASHED"),
                        InternxtFolder(uuid = "removed-folder", plainName = "removed-folder", removed = true),
                        InternxtFolder(uuid = "deleted-folder", plainName = "deleted-folder", deleted = true),
                    ),
                files =
                    listOf(
                        InternxtFile(uuid = "live-file", plainName = "live-file.txt"),
                        InternxtFile(uuid = "trashed-file", plainName = "trashed-file.txt", status = "TRASHED"),
                        InternxtFile(uuid = "removed-file", plainName = "removed-file.txt", removed = true),
                        InternxtFile(uuid = "deleted-file", plainName = "deleted-file.txt", deleted = true),
                    ),
            )

        val filtered = InternxtProvider.filterLiveContent(content)

        assertEquals(listOf("live-folder"), filtered.children.map { it.uuid })
        assertEquals(listOf("live-file"), filtered.files.map { it.uuid })
    }

    // ---- through the provider: listChildren / getMetadata / path resolution ----

    private val rootUuid = "root-folder-uuid"

    // uuid -> (children JSON, files JSON) served at /drive/folders/content/{uuid}.
    private val contents = mutableMapOf<String, Pair<String, String>>()

    private fun file(
        uuid: String,
        name: String,
        extra: String = "",
    ) = """{"uuid":"$uuid","plainName":"$name","type":"txt","size":"7"$extra}"""

    private fun folder(
        uuid: String,
        name: String,
        extra: String = "",
    ) = """{"uuid":"$uuid","plainName":"$name"$extra}"""

    private val trashed = ""","status":"TRASHED""""
    private val removedFlag = ""","status":"EXISTS","removed":true"""
    private val deletedFlag = ""","status":"EXISTS","deleted":true"""

    private fun newProvider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = java.nio.file.Files.createTempFile("ud-live-token", ".tmp")))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
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
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val uuid = path.removePrefix("/drive/folders/content/")
        val body = contents[uuid]
        return if (path.startsWith("/drive/folders/content/") && request.method == HttpMethod.Get && body != null) {
            respond("""{"children":${body.first},"files":${body.second}}""", HttpStatusCode.OK, jsonHeaders)
        } else {
            respond("""{"error":"unexpected: ${request.method} $path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    @Test
    fun `listChildren returns only live folders and files`() =
        runTest {
            contents[rootUuid] =
                "[${folder("f-live", "live-folder")},${folder("f-trashed", "t", trashed)},${folder("f-removed", "r", removedFlag)},${folder("f-deleted", "d", deletedFlag)}]" to
                "[${file("x-live", "live")},${file("x-trashed", "t", trashed)},${file("x-removed", "r", removedFlag)},${file("x-deleted", "d", deletedFlag)}]"

            val ids = newProvider().listChildren("/").map { it.id }

            assertEquals(listOf("f-live", "x-live"), ids)
        }

    @Test
    fun `getMetadata does not resolve a trashed file or folder`() =
        runTest {
            contents[rootUuid] = "[${folder("f-trashed", "gone", trashed)}]" to "[${file("x-trashed", "gone", trashed)}]"
            val provider = newProvider()

            assertFailsWith<ProviderException> { provider.getMetadata("/gone") }
            assertFailsWith<ProviderException> { provider.getMetadata("/gone.txt") }
        }

    @Test
    fun `a live item is not ambiguous with a trashed twin`() =
        runTest {
            contents[rootUuid] =
                "[${folder("f-trashed", "twin", trashed)},${folder("f-live", "twin")}]" to
                "[${file("x-trashed", "twin", trashed)},${file("x-live", "twin")}]"
            val provider = newProvider()

            assertEquals("f-live", provider.getMetadata("/twin").id)
            assertEquals("x-live", provider.getMetadata("/twin.txt").id)
        }

    @Test
    fun `a path through a folder resolves to the live twin, not a trashed one`() =
        runTest {
            contents[rootUuid] = "[${folder("f-trashed", "dir", trashed)},${folder("f-live", "dir")}]" to "[]"
            contents["f-trashed"] = "[]" to "[${file("x-stale", "stale")}]"
            contents["f-live"] = "[]" to "[${file("x-fresh", "fresh")}]"
            val provider = newProvider()

            assertEquals(listOf("x-fresh"), provider.listChildren("/dir").map { it.id })
            assertEquals("x-fresh", provider.getMetadata("/dir/fresh.txt").id)
        }

    @Test
    fun `a path through a trashed folder is not found`() =
        runTest {
            contents[rootUuid] = "[${folder("f-trashed", "dir", trashed)}]" to "[]"
            contents["f-trashed"] = "[]" to "[${file("x-stale", "stale")}]"
            val provider = newProvider()

            assertFailsWith<ProviderException> { provider.listChildren("/dir") }
            assertFailsWith<ProviderException> { provider.getMetadata("/dir/stale.txt") }
        }
}
