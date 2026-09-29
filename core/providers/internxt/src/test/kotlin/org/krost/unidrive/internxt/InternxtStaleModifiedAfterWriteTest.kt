package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

// #417: after a replace-in-place upload (PUT /files/{uuid}) and after a rename/move
// (PUT .../meta, PATCH ...) Internxt answers with the entity as it was BEFORE the server
// stamped its new modificationTime (the size/fileId in the same body are already updated,
// the timestamp is not). The engine stores that CloudItem.modified in state.db; the next
// delta lists the item with the real timestamp, the Reconciler compares the two for
// equality, sees a "remote change" against our own write and plans a download of the file
// we just uploaded. The provider must return the modified time the server will LIST next,
// which it only knows from a fresh read.
//
// Each fake server below is a two-state machine: GET .../meta answers PRE until the write
// endpoint has been hit and POST afterwards, while the write endpoint itself answers PRE.
// A provider that trusts the write response therefore returns PRE and fails these tests.
class InternxtStaleModifiedAfterWriteTest {
    private val shardUrl = "https://shard-host.invalid/stale-modified-put"
    private val bridgeFileId = "bridge-file-id-new"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val pre = "2026-05-03T16:35:45Z"
    private val post = "2026-05-03T16:36:29Z"

    private fun newProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
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
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = bucket,
            ),
        )
        return provider
    }

    private fun installMockClientOnProvider(
        provider: InternxtProvider,
        engine: MockEngine,
    ) {
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(api) as? io.ktor.client.HttpClient)?.close()
        field.set(api, io.ktor.client.HttpClient(engine))
    }

    private fun fileJson(
        uuid: String,
        plainName: String,
        size: Int,
        modified: String,
        fileId: String = "bridge-file-id-old",
        folderUuid: String = "root-folder-uuid",
    ) = """{"uuid":"$uuid","fileId":"$fileId","plainName":"$plainName","type":"txt","size":"$size",""" +
        """"bucket":"$bucket","folderUuid":"$folderUuid","status":"EXISTS",""" +
        """"creationTime":"2026-05-03T16:00:00Z","modificationTime":"$modified"}"""

    private fun folderJson(
        uuid: String,
        plainName: String,
        modified: String,
    ) = """{"uuid":"$uuid","plainName":"$plainName","parentUuid":"root-folder-uuid","status":"EXISTS",""" +
        """"creationTime":"2026-05-03T16:00:00Z","modificationTime":"$modified"}"""

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf("Content-Type", "application/json"))

    // Upload pre-amble shared by the replace tests: start -> shard PUT -> finish.
    private fun io.ktor.client.engine.mock.MockRequestHandleScope.uploadPreamble(url: String) =
        when {
            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                json("""{"uploads":[{"index":0,"uuid":"shard-u","url":"$shardUrl"}]}""")
            url == shardUrl -> respond("", HttpStatusCode.OK)
            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                json("""{"id":"$bridgeFileId","index":"${"ab".repeat(32)}","bucket":"$bucket","name":"enc"}""")
            else -> null
        }

    private inline fun withTempFile(
        name: String,
        bytes: Int,
        block: (java.nio.file.Path, java.nio.file.Path) -> Unit,
    ) {
        val tmp = java.nio.file.Files.createTempDirectory("ud-417-")
        val local = tmp.resolve(name).also { java.nio.file.Files.write(it, ByteArray(bytes) { i -> i.toByte() }) }
        try {
            block(tmp, local)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // --- Replace-in-place upload -------------------------------------------------------

    @Test
    fun `replace upload returns the modified time the server lists next, not the pre-update one from the PUT`() =
        runTest {
            withTempFile("a.txt", 510) { tmp, local ->
                val replaced = AtomicBoolean(false)
                val metaReads = AtomicInteger(0)
                val engine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val path = request.url.encodedPath
                        uploadPreamble(url)
                            ?: when {
                                path == "/drive/files/file-uuid" && request.method == HttpMethod.Put -> {
                                    replaced.set(true)
                                    // Size/fileId already new, modificationTime still the old one.
                                    json(fileJson("file-uuid", "a", 510, pre, fileId = bridgeFileId))
                                }
                                path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get -> {
                                    metaReads.incrementAndGet()
                                    json(fileJson("file-uuid", "a", 510, if (replaced.get()) post else pre, fileId = bridgeFileId))
                                }
                                else -> error("unexpected URL in replace test: $url (${request.method})")
                            }
                    }
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    val result = provider.upload(local, "/a.txt", existingRemoteId = "file-uuid", onProgress = null)

                    assertEquals("file-uuid", result.id)
                    assertEquals(510L, result.size, "size still comes from the write response")
                    assertEquals(
                        Instant.parse(post),
                        result.modified,
                        "the row must carry the post-update modified time the next delta will list; " +
                            "the PUT response's pre-update value re-arms a download of our own upload",
                    )
                    assertEquals(1, metaReads.get(), "exactly one extra GET /files/{uuid}/meta per replace")
                } finally {
                    provider.close()
                }
            }
        }

    @Test
    fun `replace upload still succeeds with the write response when the follow-up metadata read fails`() =
        runTest {
            withTempFile("a.txt", 510) { tmp, local ->
                val metaReads = AtomicInteger(0)
                val engine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val path = request.url.encodedPath
                        uploadPreamble(url)
                            ?: when {
                                path == "/drive/files/file-uuid" && request.method == HttpMethod.Put ->
                                    json(fileJson("file-uuid", "a", 510, pre, fileId = bridgeFileId))
                                path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get -> {
                                    metaReads.incrementAndGet()
                                    json("""{"message":"not found"}""", HttpStatusCode.NotFound)
                                }
                                else -> error("unexpected URL in replace test: $url (${request.method})")
                            }
                    }
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    // The bytes are already replaced on the drive: failing here would strand the
                    // tombstone and re-upload for nothing. Degrade to the old (pre-#417) value.
                    val result = provider.upload(local, "/a.txt", existingRemoteId = "file-uuid", onProgress = null)

                    assertEquals(1, metaReads.get())
                    assertEquals(Instant.parse(pre), result.modified)
                    assertEquals(510L, result.size)
                } finally {
                    provider.close()
                }
            }
        }

    // The 409 -> "identical content, adopt via replaceFile" branch of a create is a second
    // PUT /files/{uuid} call site and must carry the same fresh timestamp.
    @Test
    fun `create collision adopted via replaceFile also returns the post-update modified time`() =
        runTest {
            withTempFile("notes.txt", 64) { tmp, local ->
                val replaced = AtomicBoolean(false)
                val engine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        val path = request.url.encodedPath
                        uploadPreamble(url)
                            ?: when {
                                path == "/drive/files" && request.method == HttpMethod.Post ->
                                    json("""{"message":"File already exists"}""", HttpStatusCode.Conflict)
                                path == "/drive/folders/content/root-folder-uuid" ->
                                    // Same fileId + size as what we just uploaded -> provably identical -> adopt.
                                    json("""{"children":[],"files":[${fileJson("existing-uuid", "notes", 64, pre, fileId = bridgeFileId)}]}""")
                                path == "/drive/files/existing-uuid" && request.method == HttpMethod.Put -> {
                                    replaced.set(true)
                                    json(fileJson("existing-uuid", "notes", 64, pre, fileId = bridgeFileId))
                                }
                                path == "/drive/files/existing-uuid/meta" && request.method == HttpMethod.Get ->
                                    json(fileJson("existing-uuid", "notes", 64, if (replaced.get()) post else pre, fileId = bridgeFileId))
                                else -> error("unexpected URL in adopt test: $url (${request.method})")
                            }
                    }
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    val result = provider.upload(local, "/notes.txt", existingRemoteId = null, onProgress = null)

                    assertEquals("existing-uuid", result.id)
                    assertEquals(Instant.parse(post), result.modified)
                } finally {
                    provider.close()
                }
            }
        }

    // --- Rename / move -----------------------------------------------------------------

    @Test
    fun `file rename returns the modified time the server stamped on the rename`() =
        runTest {
            val renamed = AtomicBoolean(false)
            val metaReads = AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/drive/folders/content/root-folder-uuid" ->
                            json("""{"children":[],"files":[${fileJson("file-uuid", "a", 120, pre)}]}""")
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Put -> {
                            renamed.set(true)
                            json(fileJson("file-uuid", "a_renamed", 120, pre))
                        }
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get -> {
                            metaReads.incrementAndGet()
                            json(fileJson("file-uuid", if (renamed.get()) "a_renamed" else "a", 120, if (renamed.get()) post else pre))
                        }
                        else -> error("unexpected URL in rename test: ${request.url} (${request.method})")
                    }
                }
            val tmp = java.nio.file.Files.createTempDirectory("ud-417-mv-")
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engine)
                val result = provider.move("/a.txt", "/a_renamed.txt")

                assertEquals("file-uuid", result.id)
                assertEquals("/a_renamed.txt", result.path)
                assertEquals(Instant.parse(post), result.modified, "rename bumps the server's modified time; the row must follow")
                assertEquals(1, metaReads.get(), "exactly one extra GET /files/{uuid}/meta per move")
            } finally {
                provider.close()
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `file move into another folder returns the modified time the server stamped on the move`() =
        runTest {
            val moved = AtomicBoolean(false)
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/drive/folders/content/root-folder-uuid" ->
                            json(
                                """{"children":[${folderJson("sub-uuid", "sub", pre)}],""" +
                                    """"files":[${fileJson("file-uuid", "a", 120, pre)}]}""",
                            )
                        path == "/drive/files/file-uuid" && request.method == HttpMethod.Patch -> {
                            moved.set(true)
                            json(fileJson("file-uuid", "a", 120, pre, folderUuid = "sub-uuid"))
                        }
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                            json(fileJson("file-uuid", "a", 120, if (moved.get()) post else pre, folderUuid = "sub-uuid"))
                        else -> error("unexpected URL in move test: ${request.url} (${request.method})")
                    }
                }
            val tmp = java.nio.file.Files.createTempDirectory("ud-417-mv-")
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engine)
                val result = provider.move("/a.txt", "/sub/a.txt")

                assertEquals("/sub/a.txt", result.path)
                assertEquals(Instant.parse(post), result.modified)
            } finally {
                provider.close()
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `folder rename returns the modified time the server stamped on the rename`() =
        runTest {
            val renamed = AtomicBoolean(false)
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/drive/folders/content/root-folder-uuid" ->
                            json("""{"children":[${folderJson("dir-uuid", "dir", pre)}],"files":[]}""")
                        path == "/drive/folders/dir-uuid/meta" && request.method == HttpMethod.Put -> {
                            renamed.set(true)
                            json(folderJson("dir-uuid", "dir2", pre))
                        }
                        path == "/drive/folders/dir-uuid/meta" && request.method == HttpMethod.Get ->
                            json(folderJson("dir-uuid", if (renamed.get()) "dir2" else "dir", if (renamed.get()) post else pre))
                        else -> error("unexpected URL in folder rename test: ${request.url} (${request.method})")
                    }
                }
            val tmp = java.nio.file.Files.createTempDirectory("ud-417-mv-")
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engine)
                val result = provider.move("/dir", "/dir2")

                assertEquals("dir-uuid", result.id)
                assertEquals(true, result.isFolder)
                assertEquals(Instant.parse(post), result.modified)
            } finally {
                provider.close()
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `move still succeeds with the write response when the follow-up metadata read fails`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/drive/folders/content/root-folder-uuid" ->
                            json("""{"children":[],"files":[${fileJson("file-uuid", "a", 120, pre)}]}""")
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Put ->
                            json(fileJson("file-uuid", "a_renamed", 120, pre))
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                            json("""{"message":"not found"}""", HttpStatusCode.NotFound)
                        else -> error("unexpected URL in rename test: ${request.url} (${request.method})")
                    }
                }
            val tmp = java.nio.file.Files.createTempDirectory("ud-417-mv-")
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engine)
                // A move cannot be retried once it landed (the source path is gone), so a failed
                // follow-up read must not fail the move.
                val result = provider.move("/a.txt", "/a_renamed.txt")

                assertEquals("/a_renamed.txt", result.path)
                assertEquals(Instant.parse(pre), result.modified)
            } finally {
                provider.close()
                tmp.toFile().deleteRecursively()
            }
        }
}
