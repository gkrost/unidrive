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
import kotlin.test.assertTrue

// #417: after a replace-in-place upload (PUT /files/{uuid}) and after a rename/move
// (PUT .../meta, PATCH ...) Internxt answers with the entity as it was BEFORE the server
// stamped its new modificationTime (the size/fileId in the same body are already updated,
// the timestamp is not). The engine stores that value in state.db; the next delta lists the
// item with the real timestamp, the Reconciler compares the two for equality and plans a
// download of the file we just uploaded. The provider must return the modified time the
// server will LIST next, which it only knows from a fresh read.
//
// Each fake server below is a small state machine ([ServerClock]): GET .../meta answers PRE
// until the write endpoint has been hit, then keeps answering PRE for `staleReads` more reads
// (the read-after-write lag seen on a live drive), then POST. The write endpoint itself
// always answers PRE. A provider that trusts the write response returns PRE and fails these
// tests; one that re-reads exactly once fails the lagging variants.
class InternxtStaleModifiedAfterWriteTest {
    private val shardUrl = "https://shard-host.invalid/stale-modified-put"
    private val bridgeFileId = "bridge-file-id-new"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val pre = "2026-05-03T16:35:45Z"
    private val post = "2026-05-03T16:36:29Z"

    // What a replace sends as modificationTime: the file's local mtime. Pinned via
    // setLastModifiedTime so that, truncated to whole seconds, it equals `post` ("at least as
    // new as what we sent") while `pre` is older -- independent of the wall clock of the run.
    private val sentMtime = Instant.parse("2026-05-03T16:36:29.700Z")

    private class ServerClock(
        private val staleReads: Int = 0,
    ) {
        private val written = AtomicBoolean(false)
        private val staleServed = AtomicInteger(0)
        val reads = AtomicInteger(0)

        fun wrote() = written.set(true)

        fun hasWritten() = written.get()

        fun modified(
            pre: String,
            post: String,
        ): String {
            reads.incrementAndGet()
            if (!written.get()) return pre
            return if (staleServed.getAndIncrement() < staleReads) pre else post
        }
    }

    private fun newProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
        // No real sleeping between the bounded re-reads of a lagging meta read.
        provider.modifiedRereadDelaysMs = listOf(0L, 0L, 0L)
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
        mtime: Instant = sentMtime,
        block: (java.nio.file.Path, java.nio.file.Path) -> Unit,
    ) {
        val tmp = java.nio.file.Files.createTempDirectory("ud-417-")
        val local = tmp.resolve(name).also { java.nio.file.Files.write(it, ByteArray(bytes) { i -> i.toByte() }) }
        java.nio.file.Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.from(mtime))
        try {
            block(tmp, local)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // Fake drive for the replace-in-place tests (file-uuid, 510 bytes, pre -> post).
    private fun replaceEngine(
        clock: ServerClock,
        metaModified: (ServerClock) -> String = { it.modified(pre, post) },
    ) = MockEngine { request ->
        val url = request.url.toString()
        val path = request.url.encodedPath
        uploadPreamble(url)
            ?: when {
                path == "/drive/files/file-uuid" && request.method == HttpMethod.Put -> {
                    clock.wrote()
                    // Size/fileId already new, modificationTime still the old one.
                    json(fileJson("file-uuid", "a", 510, pre, fileId = bridgeFileId))
                }
                path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                    json(fileJson("file-uuid", "a", 510, metaModified(clock), fileId = bridgeFileId))
                else -> error("unexpected URL in replace test: $url (${request.method})")
            }
    }

    private suspend fun replaceOnce(
        engine: MockEngine,
        mtime: Instant = sentMtime,
    ): org.krost.unidrive.CloudItem {
        var result: org.krost.unidrive.CloudItem? = null
        withTempFile("a.txt", 510, mtime = mtime) { tmp, local ->
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engine)
                result = provider.upload(local, "/a.txt", existingRemoteId = "file-uuid", onProgress = null)
            } finally {
                provider.close()
            }
        }
        return result!!
    }

    // --- Replace-in-place upload -------------------------------------------------------

    @Test
    fun `replace upload returns the modified time the server lists next, not the pre-update one from the PUT`() =
        runTest {
            val clock = ServerClock()
            val result = replaceOnce(replaceEngine(clock))

            assertEquals("file-uuid", result.id)
            assertEquals(510L, result.size, "size still comes from the write response")
            assertEquals(
                Instant.parse(post),
                result.modified,
                "the row must carry the post-update modified time the next delta will list; " +
                    "the PUT response's pre-update value re-arms a download of our own upload",
            )
            assertEquals(1, clock.reads.get(), "a fresh value costs exactly one extra GET /files/{uuid}/meta")
        }

    @Test
    fun `replace upload re-reads a lagging meta until it is at least as new as the time we sent`() =
        runTest {
            // The read right after the write still shows the pre-update time (twice), then the new one.
            val clock = ServerClock(staleReads = 2)
            val result = replaceOnce(replaceEngine(clock))

            assertEquals(Instant.parse(post), result.modified)
            assertEquals(3, clock.reads.get(), "two lagging reads plus the one that finally differs")
        }

    @Test
    fun `replace upload restoring an older mtime re-reads until the meta read stops echoing the newer pre-write stamp`() =
        runTest {
            // An upload that restores an OLDER copy sends an mtime older than the item's
            // pre-write stamp. The lagging meta read then reports that NEWER pre-write value,
            // which the older-than-what-we-sent check alone accepts as final — the stale value
            // re-arms a download of the very file we just uploaded. The write response's own
            // stamp (which differs from what we sent) must be treated as lagging too.
            val restoreStamp = "2026-05-03T16:00:00Z"
            val clock = ServerClock(staleReads = 1)
            val engine = MockEngine { request ->
                val url = request.url.toString()
                val path = request.url.encodedPath
                uploadPreamble(url)
                    ?: when {
                        path == "/drive/files/file-uuid" && request.method == HttpMethod.Put -> {
                            clock.wrote()
                            // The write response still carries the item's pre-write stamp,
                            // which is NEWER than the mtime this replace sends.
                            json(fileJson("file-uuid", "a", 510, post, fileId = bridgeFileId))
                        }
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                            json(
                                fileJson(
                                    "file-uuid", "a", 510,
                                    clock.modified(pre = post, post = restoreStamp),
                                    fileId = bridgeFileId,
                                ),
                            )
                        else -> error("unexpected URL in restore-older test: $url (${request.method})")
                    }
            }

            val result = replaceOnce(engine, mtime = Instant.parse("2026-05-03T16:00:00.700Z"))

            assertEquals(
                Instant.parse(restoreStamp),
                result.modified,
                "the row must carry the stamp for the restored copy, not the newer pre-write echo",
            )
            assertEquals(2, clock.reads.get(), "the echoing read plus the one with the fresh stamp")
        }

    @Test
    fun `replace upload re-reads when the meta read returns an unparseable modified time`() =
        runTest {
            // A garbage timestamp cannot be evaluated against either staleness clause; accepting
            // it would store an unparseable value and the next listing would not match it.
            val clock = ServerClock(staleReads = 0)
            var garbageServed = 0
            val engine = replaceEngine(clock, metaModified = { c ->
                // Count the garbage read on the clock too, so the read-count assertion sees it.
                if (c.hasWritten() && garbageServed++ == 0) {
                    c.reads.incrementAndGet()
                    "not-a-timestamp"
                } else {
                    c.modified(pre, post)
                }
            })

            val result = replaceOnce(engine)

            assertEquals(Instant.parse(post), result.modified)
            assertEquals(2, clock.reads.get(), "the garbage read plus the one with a parseable stamp")
        }

    @Test
    fun `replace upload whose meta never catches up returns the last value after a bounded number of reads`() =
        runTest {
            val clock = ServerClock(staleReads = Int.MAX_VALUE)
            val result = replaceOnce(replaceEngine(clock))

            assertEquals(Instant.parse(pre), result.modified, "keeps the last value read, does not throw")
            assertEquals(4, clock.reads.get(), "the first read plus at most the three configured re-reads")
        }

    @Test
    fun `replace with an unchanged mtime does not retry because nothing newer is expected`() =
        runTest {
            // A replace whose local mtime equals the old modified time: sent == old == listed.
            val clock = ServerClock()
            var result: org.krost.unidrive.CloudItem? = null
            withTempFile("a.txt", 510, mtime = Instant.parse(pre)) { tmp, local ->
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, replaceEngine(clock) { it.modified(pre, pre) })
                    result = provider.upload(local, "/a.txt", existingRemoteId = "file-uuid", onProgress = null)
                } finally {
                    provider.close()
                }
            }

            assertEquals(Instant.parse(pre), result!!.modified)
            assertEquals(1, clock.reads.get(), "a value already equal to what we sent is not lagging: no retry")
        }

    @Test
    fun `replace upload still succeeds with the write response when the follow-up metadata read fails`() =
        runTest {
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
            // The bytes are already replaced on the drive: failing here would strand the
            // tombstone and re-upload for nothing. Degrade to the old (pre-#417) value.
            val result = replaceOnce(engine)

            assertEquals(1, metaReads.get())
            assertEquals(Instant.parse(pre), result.modified)
            assertEquals(510L, result.size)
        }

    // The 409 -> "identical content, adopt via replaceFile" branch of a create is a second
    // PUT /files/{uuid} call site and must carry the same fresh timestamp.
    @Test
    fun `create collision adopted via replaceFile also returns the post-update modified time`() =
        runTest {
            withTempFile("notes.txt", 64) { tmp, local ->
                val clock = ServerClock(staleReads = 1)
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
                                    clock.wrote()
                                    json(fileJson("existing-uuid", "notes", 64, pre, fileId = bridgeFileId))
                                }
                                path == "/drive/files/existing-uuid/meta" && request.method == HttpMethod.Get ->
                                    json(fileJson("existing-uuid", "notes", 64, clock.modified(pre, post), fileId = bridgeFileId))
                                else -> error("unexpected URL in adopt test: $url (${request.method})")
                            }
                    }
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    val result = provider.upload(local, "/notes.txt", existingRemoteId = null, onProgress = null)

                    assertEquals("existing-uuid", result.id)
                    assertEquals(Instant.parse(post), result.modified)
                    assertEquals(2, clock.reads.get(), "one lagging read, then the new value")
                } finally {
                    provider.close()
                }
            }
        }

    // --- Rename / move -----------------------------------------------------------------

    // Fake drive for a file rename: a.txt -> a_renamed.txt (file-uuid, listed at `pre`).
    private fun renameEngine(clock: ServerClock) =
        MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/drive/folders/content/root-folder-uuid" ->
                    json("""{"children":[],"files":[${fileJson("file-uuid", "a", 120, pre)}]}""")
                path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Put -> {
                    clock.wrote()
                    json(fileJson("file-uuid", "a_renamed", 120, pre))
                }
                path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                    json(fileJson("file-uuid", if (clock.hasWritten()) "a_renamed" else "a", 120, clock.modified(pre, post)))
                else -> error("unexpected URL in rename test: ${request.url} (${request.method})")
            }
        }

    private suspend fun moveOnce(
        engine: MockEngine,
        from: String,
        to: String,
    ): org.krost.unidrive.CloudItem {
        val tmp = java.nio.file.Files.createTempDirectory("ud-417-mv-")
        val provider = newProviderRooted(tmp)
        try {
            installMockClientOnProvider(provider, engine)
            return provider.move(from, to)
        } finally {
            provider.close()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `file rename returns the modified time the server stamped on the rename`() =
        runTest {
            val clock = ServerClock()
            val result = moveOnce(renameEngine(clock), "/a.txt", "/a_renamed.txt")

            assertEquals("file-uuid", result.id)
            assertEquals("/a_renamed.txt", result.path)
            assertEquals(Instant.parse(post), result.modified, "rename bumps the server's modified time; the row must follow")
            assertEquals(1, clock.reads.get(), "exactly one extra GET /files/{uuid}/meta per move")
        }

    @Test
    fun `file rename re-reads a lagging meta until the modified time differs from the pre-write one`() =
        runTest {
            val clock = ServerClock(staleReads = 2)
            val result = moveOnce(renameEngine(clock), "/a.txt", "/a_renamed.txt")

            assertEquals(Instant.parse(post), result.modified)
            assertEquals(3, clock.reads.get(), "two lagging reads plus the one that finally differs")
        }

    @Test
    fun `file rename whose meta never changes returns the last value after a bounded number of reads`() =
        runTest {
            val clock = ServerClock(staleReads = Int.MAX_VALUE)
            val result = moveOnce(renameEngine(clock), "/a.txt", "/a_renamed.txt")

            assertEquals("/a_renamed.txt", result.path, "the move itself must not fail")
            assertEquals(Instant.parse(pre), result.modified, "keeps the last value read, does not throw")
            assertEquals(4, clock.reads.get(), "the first read plus at most the three configured re-reads")
        }

    @Test
    fun `file move into another folder returns the modified time the server stamped on the move`() =
        runTest {
            // Lagging once: the first read after the PATCH still shows the pre-move time.
            val clock = ServerClock(staleReads = 1)
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
                            clock.wrote()
                            json(fileJson("file-uuid", "a", 120, pre, folderUuid = "sub-uuid"))
                        }
                        path == "/drive/files/file-uuid/meta" && request.method == HttpMethod.Get ->
                            json(fileJson("file-uuid", "a", 120, clock.modified(pre, post), folderUuid = "sub-uuid"))
                        else -> error("unexpected URL in move test: ${request.url} (${request.method})")
                    }
                }
            val result = moveOnce(engine, "/a.txt", "/sub/a.txt")

            assertEquals("/sub/a.txt", result.path)
            assertEquals(Instant.parse(post), result.modified)
            assertEquals(2, clock.reads.get(), "one lagging read, then the new value")
        }

    @Test
    fun `folder rename returns the modified time the server stamped on the rename`() =
        runTest {
            val clock = ServerClock(staleReads = 1)
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/drive/folders/content/root-folder-uuid" ->
                            json("""{"children":[${folderJson("dir-uuid", "dir", pre)}],"files":[]}""")
                        path == "/drive/folders/dir-uuid/meta" && request.method == HttpMethod.Put -> {
                            clock.wrote()
                            json(folderJson("dir-uuid", "dir2", pre))
                        }
                        path == "/drive/folders/dir-uuid/meta" && request.method == HttpMethod.Get ->
                            json(folderJson("dir-uuid", if (clock.hasWritten()) "dir2" else "dir", clock.modified(pre, post)))
                        else -> error("unexpected URL in folder rename test: ${request.url} (${request.method})")
                    }
                }
            val result = moveOnce(engine, "/dir", "/dir2")

            assertEquals("dir-uuid", result.id)
            assertEquals(true, result.isFolder)
            assertEquals(Instant.parse(post), result.modified)
            assertEquals(2, clock.reads.get(), "one lagging read, then the new value")
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
            // A move cannot be retried once it landed (the source path is gone), so a failed
            // follow-up read must not fail the move.
            val result = moveOnce(engine, "/a.txt", "/a_renamed.txt")

            assertEquals("/a_renamed.txt", result.path)
            assertEquals(Instant.parse(pre), result.modified)
        }

    @Test
    fun `the default re-read delays cap the extra wait at about two seconds`() {
        val tmp = java.nio.file.Files.createTempDirectory("ud-417-delays-")
        val provider = InternxtProvider(InternxtConfig(tokenPath = tmp))
        try {
            val delays = provider.modifiedRereadDelaysMs
            assertEquals(3, delays.size)
            assertTrue(delays.sum() <= 2_000L, "extra latency per replace/move must stay bounded, was ${delays.sum()} ms")
        } finally {
            provider.close()
            tmp.toFile().deleteRecursively()
        }
    }
}
