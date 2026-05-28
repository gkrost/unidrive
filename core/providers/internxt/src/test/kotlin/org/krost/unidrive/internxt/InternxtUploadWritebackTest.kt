package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Upload write-back recovery: a createFile whose response is LOST (the
// "server prematurely closed the connection" truncation) AFTER the server
// committed the drive entry must not leave a local: ghost row. The file is on
// the drive but the client never got its UUID; the provider re-resolves by path
// and adopts the real UUID when the landed file is provably ours.
//
// If these tests are removed or loosened, a truncated upload response silently
// produces a never-recorded file -> lost renames/deletes through the mount and a
// duplicate-upload hazard on the next sync. retryOnTransient only retries
// InternxtApiException transient statuses, so the raw IOException reaches the
// provider-level catch (verified). Three invariants, one test each:
//  1. landed-and-ours     -> adopt the real UUID, no throw, no re-upload.
//  2. not-found           -> re-throw the original failure (engine retries later).
//  3. found-but-different -> re-throw (never adopt a file that isn't our content).
class InternxtUploadWritebackTest {
    private val shardUrl = "https://shard-host.invalid/writeback-put"
    private val localFileId = "local-bucket-entry-id" // fileId of what we just uploaded

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
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    private fun installMockClientOnProvider(
        provider: InternxtProvider,
        engine: io.ktor.client.engine.mock.MockEngine,
    ) {
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(api) as? io.ktor.client.HttpClient)?.close()
        field.set(api, io.ktor.client.HttpClient(engine))
    }

    // Builds a MockEngine whose upload pre-amble succeeds, whose createFile POST
    // fails with a connection-truncation IOException, and whose folder-content GET
    // returns folderFilesJson. createPosts / folderGets count the calls.
    private fun engineWithLostCreate(
        createPosts: AtomicInteger,
        folderGets: AtomicInteger,
        replacePuts: AtomicInteger,
        folderFilesJson: String,
    ) = io.ktor.client.engine.mock.MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                respond(
                    content = """{"uploads":[{"index":0,"uuid":"shard-u","url":"$shardUrl"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            url == shardUrl -> respond("", HttpStatusCode.OK)
            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                respond(
                    content = """{"id":"$localFileId","index":"${"ab".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            // createFile POST → simulate the lost/truncated response.
            url.startsWith("https://gateway.internxt.com/drive/files") &&
                request.method == HttpMethod.Post -> {
                createPosts.incrementAndGet()
                throw java.io.IOException("Failed to parse HTTP response: the server prematurely closed the connection")
            }
            // replaceFile PUT — must NOT be hit by the recovery path.
            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                request.method == HttpMethod.Put -> {
                replacePuts.incrementAndGet()
                respond("", HttpStatusCode.OK)
            }
            // re-resolve listing
            url.contains("/drive/folders/content/") -> {
                folderGets.incrementAndGet()
                respond(
                    content = folderFilesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
            else -> error("unexpected URL in writeback test: $url (method=${request.method})")
        }
    }

    // --- Invariant 1: landed + ours → adopt the real UUID, no throw, no re-upload.
    @Test
    fun `lost create response but file landed with our content adopts the real uuid`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-wb-adopt-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val createPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val replacePuts = AtomicInteger(0)
                // Landed file: same fileId we uploaded AND same size (200) → provably ours.
                val landed =
                    """{"children":[],"files":[{
                        "uuid":"landed-real-uuid",
                        "fileId":"$localFileId",
                        "plainName":"deb-x","type":"deb","size":"200",
                        "bucket":"6928426c1a2316b856c9ab81",
                        "folderUuid":"root-folder-uuid","status":"EXISTS"
                    }]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostCreate(createPosts, folderGets, replacePuts, landed),
                    )
                    // Upload to the exact name the landed listing reports (deb-x.deb).
                    val result = provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)

                    assertEquals("landed-real-uuid", result.id, "must adopt the UUID of the file that landed server-side")
                    assertEquals(1, createPosts.get(), "createFile attempted exactly once (no blind re-POST)")
                    assertTrue(folderGets.get() >= 1, "must re-resolve the parent to confirm the server-side commit")
                    assertEquals(0, replacePuts.get(), "adoption must not overwrite via replaceFile")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    // --- Invariant 2: file not found on re-resolve → re-throw the original failure.
    @Test
    fun `lost create response and file not landed re-throws`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-wb-absent-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val createPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val replacePuts = AtomicInteger(0)
                val empty = """{"children":[],"files":[]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostCreate(createPosts, folderGets, replacePuts, empty),
                    )
                    assertFailsWith<java.io.IOException> {
                        provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)
                    }
                    assertTrue(folderGets.get() >= 1, "re-resolve must run before deciding to re-throw")
                    assertEquals(0, replacePuts.get(), "no overwrite when nothing landed")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    // --- Invariant 3: found by name but different content → re-throw (never adopt
    //     a file that isn't ours).
    @Test
    fun `lost create response but landed content differs re-throws`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-wb-diff-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val createPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val replacePuts = AtomicInteger(0)
                // Same name, but DIFFERENT fileId and size → not provably ours.
                val different =
                    """{"children":[],"files":[{
                        "uuid":"someone-elses-uuid",
                        "fileId":"a-different-bucket-entry",
                        "plainName":"deb-x","type":"deb","size":"999999",
                        "bucket":"6928426c1a2316b856c9ab81",
                        "folderUuid":"root-folder-uuid","status":"EXISTS"
                    }]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostCreate(createPosts, folderGets, replacePuts, different),
                    )
                    assertFailsWith<java.io.IOException> {
                        provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)
                    }
                    assertEquals(0, replacePuts.get(), "must never overwrite a file whose content isn't provably ours")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    // ---------------------------------------------------------------------------
    // Same data-loss class, one step earlier in the pipeline: the finishUpload /
    // shard-commit whose response is LOST after the bridge committed the bucket
    // entry. commitWithRetry only reconciled the 409 MissingUploadsError case; the
    // raw IOException ("server prematurely closed the connection") propagated out
    // of retryShardCommit, the createFile step never ran, and the engine recorded
    // a local: ghost row (remote_size=0). The provider now re-resolves the parent
    // by (name, type, size, window) and adopts the landed bucket entry, so the
    // normal createFile writeback yields a CloudItem carrying the real drive UUID.
    //
    // If these tests are removed or loosened, a truncated finishUpload response
    // silently produces a never-recorded file -> lost renames/deletes through the
    // mount and a duplicate-upload hazard on the next sync. Three invariants:
    //  1. landed-and-ours      -> reconcile recovers the fileId; upload() returns a
    //     real UUID (not local:), finishUpload fired exactly once (no re-finish).
    //  2. nothing-landed       -> upload() re-throws (no createFile, no ghost row).
    //  3. pre-commit IO failure -> upload() re-throws WITHOUT adopting, even when a
    //     coincidental recent same-name/size file exists. A connect reset/timeout is
    //     no proof the request committed; adopting by name+size would claim a file
    //     that isn't ours. Only a lost-RESPONSE IOException may reconcile-by-name.
    private val lostResponseMsg = "Failed to parse HTTP response: the server prematurely closed the connection"
    private val preCommitConnectMsg = "Connection reset by peer"

    // Builds a MockEngine whose upload pre-amble succeeds, whose finishUpload POST
    // fails with [finishIoMessage], whose folder-content GET returns folderFilesJson
    // (the finish-reconcile listing), and whose createFile POST succeeds returning
    // the real drive UUID. finishPosts / folderGets / createPosts count the calls.
    private fun engineWithLostFinish(
        finishPosts: AtomicInteger,
        folderGets: AtomicInteger,
        createPosts: AtomicInteger,
        folderFilesJson: String,
        createdUuid: String,
        finishIoMessage: String = lostResponseMsg,
    ) = io.ktor.client.engine.mock.MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                respond(
                    content = """{"uploads":[{"index":0,"uuid":"shard-u","url":"$shardUrl"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            url == shardUrl -> respond("", HttpStatusCode.OK)
            // finishUpload POST → simulate the configured IO failure.
            url.contains("/v2/buckets/") && url.contains("/files/finish") -> {
                finishPosts.incrementAndGet()
                throw java.io.IOException(finishIoMessage)
            }
            // finish-reconcile listing.
            url.contains("/drive/folders/content/") -> {
                folderGets.incrementAndGet()
                respond(
                    content = folderFilesJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
            // createFile POST → succeeds with the real drive UUID once the
            // reconcile handed us the bucket-entry fileId.
            url.startsWith("https://gateway.internxt.com/drive/files") &&
                request.method == HttpMethod.Post -> {
                createPosts.incrementAndGet()
                respond(
                    content =
                        """{"uuid":"$createdUuid","fileId":"$localFileId","plainName":"deb-x",""" +
                            """"type":"deb","size":"200","bucket":"6928426c1a2316b856c9ab81",""" +
                            """"folderUuid":"root-folder-uuid","status":"EXISTS"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
            else -> error("unexpected URL in lost-finish test: $url (method=${request.method})")
        }
    }

    // --- Invariant 1: finish truncates but the shard landed → reconcile recovers
    //     the fileId; upload() returns a real UUID, no blind re-finish.
    @Test
    fun `lost finish response but shard landed adopts the bucket entry and writes a real uuid`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-fin-adopt-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val finishPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val createPosts = AtomicInteger(0)
                // Landed bucket entry: same plainName/type/size we uploaded, fresh
                // creationTime inside the reconcile window → provably ours.
                val landed =
                    """{"children":[],"files":[{
                        "uuid":"intermediate-uuid",
                        "fileId":"$localFileId",
                        "plainName":"deb-x","type":"deb","size":"200",
                        "bucket":"6928426c1a2316b856c9ab81",
                        "folderUuid":"root-folder-uuid","status":"EXISTS",
                        "creationTime":"${java.time.Instant.now()}"
                    }]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostFinish(finishPosts, folderGets, createPosts, landed, "real-drive-uuid"),
                    )
                    val result = provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)

                    assertEquals("real-drive-uuid", result.id, "writeback CloudItem must carry the real drive UUID, not a local: ghost")
                    assertTrue(!result.id.startsWith("local:"), "the recovered identity must never be a local: synthetic id")
                    assertEquals(1, finishPosts.get(), "finishUpload fired exactly once (the IOException is reconciled, not blindly re-finished)")
                    assertTrue(folderGets.get() >= 1, "must re-resolve the parent to confirm the server-side commit")
                    assertEquals(1, createPosts.get(), "createFile runs once with the reconciled bucket-entry fileId")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    // --- Invariant 2: finish truncates and nothing landed → upload() re-throws,
    //     no createFile, no ghost row.
    @Test
    fun `lost finish response and shard not landed re-throws without creating a file`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-fin-absent-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val finishPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val createPosts = AtomicInteger(0)
                val empty = """{"children":[],"files":[]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostFinish(finishPosts, folderGets, createPosts, empty, "real-drive-uuid"),
                    )
                    assertFailsWith<java.io.IOException> {
                        provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)
                    }
                    assertTrue(folderGets.get() >= 1, "re-resolve must run before deciding to re-throw")
                    assertEquals(0, createPosts.get(), "no createFile when the shard never committed — never fabricate a drive entry")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    // --- Invariant 3: a PRE-COMMIT IO failure (connect reset/timeout) must NOT
    //     adopt by name+size, even when a coincidental recent same-name/size file
    //     already exists in the parent. A connect failure is no proof the request
    //     committed; adopting would claim a file that isn't ours. upload() re-throws
    //     and never reaches createFile.
    @Test
    fun `pre-commit connect failure never adopts a coincidental same-name file`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-fin-precommit-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "deb-", ".deb")
                    .also { java.nio.file.Files.write(it, ByteArray(200) { i -> i.toByte() }) }
            try {
                val finishPosts = AtomicInteger(0)
                val folderGets = AtomicInteger(0)
                val createPosts = AtomicInteger(0)
                // A recent, same-name/type/size file that would MATCH the reconcile
                // listing — but the failure proves nothing committed, so it must be
                // ignored, not adopted.
                val coincidental =
                    """{"children":[],"files":[{
                        "uuid":"not-ours-uuid",
                        "fileId":"some-other-bucket-entry",
                        "plainName":"deb-x","type":"deb","size":"200",
                        "bucket":"6928426c1a2316b856c9ab81",
                        "folderUuid":"root-folder-uuid","status":"EXISTS",
                        "creationTime":"${java.time.Instant.now()}"
                    }]}"""
                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(
                        provider,
                        engineWithLostFinish(
                            finishPosts,
                            folderGets,
                            createPosts,
                            coincidental,
                            "real-drive-uuid",
                            finishIoMessage = preCommitConnectMsg,
                        ),
                    )
                    assertFailsWith<java.io.IOException> {
                        provider.upload(local, "/deb-x.deb", existingRemoteId = null, onProgress = null)
                    }
                    assertEquals(0, folderGets.get(), "a pre-commit failure must NOT even list the parent to adopt")
                    assertEquals(0, createPosts.get(), "never createFile off a coincidental match after a pre-commit failure")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
