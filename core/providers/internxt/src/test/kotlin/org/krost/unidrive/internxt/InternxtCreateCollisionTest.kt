package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-366 data-loss regression: create-collision keep-both.
 *
 * When POST /files returns 409 and existingRemoteId=null (DB-drift / stale
 * listing), the only safe silent overwrite is when the remote file is
 * provably byte-identical to the local upload.  For any other case the
 * remote must be preserved untouched; the local content lands under a
 * conflict-suffixed name.
 *
 * Content-identity signal: InternxtFile.fileId (bridge bucket-entry ID) +
 * size.  The bucket-entry ID is Internxt's content-address for the
 * encrypted blob; equal fileId AND equal size ⇒ identical content.
 * Different size, OR same size but different fileId, OR fileId unavailable
 * (null) ⇒ not provably identical → keep-both.
 *
 * Four orthogonal invariants, one test each:
 *  1. [create_collision_with_differing_remote_keeps_both_does_not_overwrite]
 *     — the data-loss regression.  replaceFile MUST NOT be called; local
 *     content lands as a conflict copy.
 *  2. [create_collision_with_identical_remote_adopts_no_conflict_copy]
 *     — identical bucket-entry + size → adopt (replaceFile); no conflict
 *     createFile.
 *  3. [create_collision_on_folder_path_still_throws]
 *     — existing item is a folder → original exception re-thrown unchanged.
 *  4. [conflict_name_helper_appends_counter_on_second_collision]
 *     — name helper: base → base (2) → base (3) …
 */
class InternxtCreateCollisionTest {

    // -------------------------------------------------------------------------
    // Shared test infrastructure (mirrors tombstone-test helpers in
    // InternxtApiServiceTest)
    // -------------------------------------------------------------------------

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
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = fakeJwt,
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

    /**
     * Standard upload pre-amble responses: startUpload + PUT + finishUpload.
     * These three are identical across all collision tests; only the POST
     * /files and GET /folders/content responses differ.
     */
    private val shardUrl = "https://shard-host.invalid/collision-put"
    private val localFileId = "local-bucket-entry-id"       // what we just uploaded
    private val remoteFileId = "remote-bucket-entry-different" // a DIFFERENT remote content

    private fun uploadPreambleRoutes(
        request: io.ktor.client.request.HttpRequestData,
        startCalls: AtomicInteger,
        putCalls: AtomicInteger,
        finishCalls: AtomicInteger,
    ): io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> Unit =
        { -> { -> } } // unused — inlined below for readability

    // -------------------------------------------------------------------------
    // TEST 1 (non-negotiable regression guard)
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: a remote file whose content is NOT provably identical
     * to the local upload must NEVER be overwritten/replaced.
     *
     * Scenario:
     *  - POST /files → 409 (path exists on remote, existingRemoteId=null)
     *  - GET /folders/content → remote file with DIFFERENT fileId and DIFFERENT size
     *  - Expected: replaceFile (PUT) is NEVER called; a second POST /files is
     *    called with a conflict-suffixed name carrying the local content.
     */
    @Test
    fun `create_collision_with_differing_remote_keeps_both_does_not_overwrite`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-diff-")
            // Uploaded file: "AGENTS.md" (31 KB plaintext — real size doesn't matter
            // for the HTTP-mock path; we use a small actual file here)
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "AGENTS-", ".md")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(200) { it.toByte() }) }
            try {
                val startCalls = AtomicInteger(0)
                val putCalls = AtomicInteger(0)
                val finishCalls = AtomicInteger(0)
                val replaceFileCalls = AtomicInteger(0)
                val createFileCalls = AtomicInteger(0)
                val folderContentCalls = AtomicInteger(0)
                // Capture the plainName sent to each POST /files so we can assert
                // the first was "AGENTS" (the original attempt) and the second
                // carries a conflict-suffix.
                val capturedCreatePlainNames =
                    java.util.Collections.synchronizedList(mutableListOf<String>())

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            // startUpload
                            url.contains("/v2/buckets/") && url.contains("/files/start") -> {
                                startCalls.incrementAndGet()
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // PUT shard
                            url == shardUrl -> {
                                putCalls.incrementAndGet()
                                respond("", HttpStatusCode.OK)
                            }
                            // finishUpload
                            url.contains("/v2/buckets/") && url.contains("/files/finish") -> {
                                finishCalls.incrementAndGet()
                                respond(
                                    content = """{"id":"$localFileId","index":"${"aa".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc-agents"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // POST /drive/files — first call is the original (409);
                            // second call is the conflict copy (OK).
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                val bodyText =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.ByteArrayContent ->
                                            String(b.bytes(), Charsets.UTF_8)
                                        is io.ktor.http.content.TextContent -> b.text
                                        else -> "{}"
                                    }
                                val parsed = Json.parseToJsonElement(bodyText).jsonObject
                                val pn = parsed["plainName"]?.toString()?.trim('"') ?: ""
                                capturedCreatePlainNames.add(pn)
                                val n = createFileCalls.incrementAndGet()
                                if (n == 1) {
                                    // First POST → 409 (simulates path-already-exists)
                                    respond(
                                        content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                        status = HttpStatusCode.Conflict,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                } else {
                                    // Second POST → conflict copy succeeds
                                    respond(
                                        content = """{"uuid":"conflict-copy-uuid","plainName":"$pn","type":"md","size":"200","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                }
                            }
                            // PUT /drive/files/{uuid} — replaceFile
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"replaced-uuid","plainName":"AGENTS","type":"md","size":"200","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // GET /drive/folders/content/{uuid} — used by the UD-366 fallback
                            // to inspect the existing remote file's content signal.
                            url.contains("/drive/folders/content/") -> {
                                folderContentCalls.incrementAndGet()
                                // Remote has DIFFERENT fileId AND different size → not identical
                                respond(
                                    content =
                                        """{"children":[],"files":[{
                                            "uuid":"existing-file-uuid",
                                            "fileId":"$remoteFileId",
                                            "plainName":"AGENTS",
                                            "type":"md",
                                            "size":"31744",
                                            "bucket":"6928426c1a2316b856c9ab81",
                                            "folderUuid":"root-folder-uuid",
                                            "status":"EXISTS"
                                        }]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            else -> error("unexpected URL in collision-diff test: $url (method=${request.method})")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    provider.upload(local, "/AGENTS.md", existingRemoteId = null, onProgress = null)

                    // THE non-negotiable assertion: the 31 KB remote MUST NOT be overwritten.
                    assertEquals(
                        0,
                        replaceFileCalls.get(),
                        "REGRESSION GUARD: replaceFile (PUT) must NEVER be called when remote content differs — " +
                            "calling it would silently destroy the 31 KB remote file",
                    )

                    // Local content must land as a conflict copy (second POST /files).
                    assertEquals(
                        2,
                        createFileCalls.get(),
                        "createFile must be called twice: once for the original path (→ 409) " +
                            "and once for the conflict-suffixed copy",
                    )

                    // The second POST must carry a conflict-suffix in plainName.
                    assertTrue(
                        capturedCreatePlainNames.size == 2,
                        "exactly two POST /files calls captured",
                    )
                    val conflictPlainName = capturedCreatePlainNames[1]
                    assertTrue(
                        conflictPlainName.contains("conflict", ignoreCase = true),
                        "conflict copy plainName must contain 'conflict'; got: '$conflictPlainName'",
                    )
                    assertFalse(
                        conflictPlainName == capturedCreatePlainNames[0],
                        "conflict copy plainName must differ from the original",
                    )

                    // getFolderContents must have been called to inspect the remote.
                    assertTrue(
                        folderContentCalls.get() >= 1,
                        "folders/content must be queried to read the remote file's content signal",
                    )
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // TEST 2
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: when the remote file is provably byte-identical to
     * the local upload (same bucket-entry fileId AND same size), the adopt
     * path (replaceFile) is used and NO conflict copy is created.
     *
     * This is safe because equal fileId + size ⇒ the same encrypted blob is
     * already registered on the remote — replaceFile is a no-op-equivalent
     * re-link, not a destructive overwrite.
     */
    @Test
    fun `create_collision_with_identical_remote_adopts_no_conflict_copy`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-ident-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "notes-", ".txt")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(64) { 0x41 }) }
            try {
                val replaceFileCalls = AtomicInteger(0)
                val createFileCalls = AtomicInteger(0)

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-u","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url == shardUrl ->
                                respond("", HttpStatusCode.OK)
                            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"$localFileId","index":"${"cc".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc-notes"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                val n = createFileCalls.incrementAndGet()
                                if (n == 1) {
                                    respond(
                                        content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                        status = HttpStatusCode.Conflict,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                } else {
                                    // Should not be reached for the identical case
                                    respond(
                                        content = """{"uuid":"x","plainName":"notes","type":"txt","size":"64","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                }
                            }
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"existing-file-uuid","plainName":"notes","type":"txt","size":"64","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.contains("/drive/folders/content/") ->
                                // IDENTICAL: same fileId as what we just uploaded, same size (64)
                                respond(
                                    content =
                                        """{"children":[],"files":[{
                                            "uuid":"existing-file-uuid",
                                            "fileId":"$localFileId",
                                            "plainName":"notes",
                                            "type":"txt",
                                            "size":"64",
                                            "bucket":"6928426c1a2316b856c9ab81",
                                            "folderUuid":"root-folder-uuid",
                                            "status":"EXISTS"
                                        }]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            else -> error("unexpected URL in collision-ident test: $url")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    provider.upload(local, "/notes.txt", existingRemoteId = null, onProgress = null)

                    assertEquals(
                        1,
                        replaceFileCalls.get(),
                        "identical content → adopt via replaceFile exactly once",
                    )
                    assertEquals(
                        1,
                        createFileCalls.get(),
                        "only the original 409 POST; no second POST for a conflict copy",
                    )
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // TEST 3
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: when the existing item at the conflicting path is a
     * FOLDER (not a file), the original 409 exception is re-thrown unchanged.
     * The folder must not be touched and no conflict copy is created.
     */
    @Test
    fun `create_collision_on_folder_path_still_throws`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-folder-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "thing-", ".bin")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(8) { 0x00 }) }
            try {
                val replaceFileCalls = AtomicInteger(0)
                val createFileCallsAfterFirst = AtomicInteger(0)

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-f","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url == shardUrl ->
                                respond("", HttpStatusCode.OK)
                            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"local-fid","index":"${"dd".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                // First call is the original → 409; any subsequent POST
                                // (should not happen) increments a separate counter.
                                val bodyText =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.ByteArrayContent ->
                                            String(b.bytes(), Charsets.UTF_8)
                                        is io.ktor.http.content.TextContent -> b.text
                                        else -> "{}"
                                    }
                                val pn =
                                    Json.parseToJsonElement(bodyText).jsonObject["plainName"]
                                        ?.toString()?.trim('"') ?: ""
                                // Count any POST after the first (would be a wrongly-created conflict copy)
                                if (pn.contains("conflict")) createFileCallsAfterFirst.incrementAndGet()
                                respond(
                                    content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                    status = HttpStatusCode.Conflict,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond("", HttpStatusCode.OK)
                            }
                            url.contains("/drive/folders/content/") ->
                                // The path "thing.bin" resolves as a FOLDER (edge case but possible
                                // if a folder has that name)
                                respond(
                                    content =
                                        """{"children":[{
                                            "uuid":"folder-uuid-existing",
                                            "plainName":"thing.bin",
                                            "name":"enc-thing",
                                            "status":"EXISTS"
                                        }],"files":[]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            else -> error("unexpected URL in collision-folder test: $url")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    kotlin.test.assertFailsWith<InternxtApiException> {
                        provider.upload(local, "/thing.bin", existingRemoteId = null, onProgress = null)
                    }
                    assertEquals(0, replaceFileCalls.get(), "replaceFile must not be called for a folder conflict")
                    assertEquals(0, createFileCallsAfterFirst.get(), "no conflict copy must be created for a folder conflict")
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // TEST 4
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: the conflict-name helper [InternxtProvider.conflictName]
     * appends a numeric counter suffix to avoid a second collision when the
     * first conflict-name is itself already taken.
     *
     * Naming contract:
     *   base = "$plainName (conflict $date)"
     *   counter 1 → base            (e.g. "AGENTS (conflict 2026-05-26)")
     *   counter 2 → "$base (2)"     (e.g. "AGENTS (conflict 2026-05-26) (2)")
     *   counter 3 → "$base (3)"     …
     */
    @Test
    fun `conflict_name_helper_appends_counter_on_second_collision`() {
        val date = "2026-05-26"
        val first = InternxtProvider.conflictName("AGENTS", date, 1)
        val second = InternxtProvider.conflictName("AGENTS", date, 2)
        val third = InternxtProvider.conflictName("AGENTS", date, 3)

        assertEquals("AGENTS (conflict $date)", first)
        assertEquals("AGENTS (conflict $date) (2)", second)
        assertEquals("AGENTS (conflict $date) (3)", third)
    }
}
