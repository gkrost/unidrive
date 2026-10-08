package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.hydration.CreateResult
import org.krost.unidrive.hydration.DehydrateResult
import org.krost.unidrive.hydration.HydrateResult
import org.krost.unidrive.hydration.Hydration
import org.krost.unidrive.hydration.HydrationError
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.LastSyncedResult
import org.krost.unidrive.hydration.ListResult
import org.krost.unidrive.hydration.MkdirResult
import org.krost.unidrive.hydration.OpenResult
import org.krost.unidrive.hydration.RenameResult
import org.krost.unidrive.hydration.RmdirResult
import org.krost.unidrive.hydration.UnlinkResult
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden NDJSON contract corpus for the daemon's IPC wire (issue: one
 * versioned, executable contract spine shared by the JVM daemon and the
 * Rust/C# co-clients instead of compatibility-by-inspection).
 *
 * Corpus layout: `src/test/resources/ipc-contract/<verb>.ndjson`, one file
 * per IPC verb the daemon registers. Each file holds canonical
 * request/response pairs as alternating NDJSON lines (odd line = request,
 * even line = response). Co-client repos vendor these files and replay them
 * against their own encoder/decoder.
 *
 * The daemon authenticates every connection first (docs/dev/specs/ipc-authentication.md); the
 * handshake itself and its refusal shapes are pinned by the session transcripts under
 * `ipc-contract/auth/` (one connection per file, request/reply pairs in order).
 * Lines the daemon writes on a connection without a request (no verb) live
 * in `ipc-contract/connection/<error token>.ndjson`, one line each: today
 * only `too_many_clients`, the line a connection over the cap reads before
 * the daemon closes it.
 *
 * Comparison is parse-and-compare (key-order-insensitive), never strcmp.
 * A small set of VOLATILE_FIELDS carry machine- or run-dependent values
 * (uuids, uptimes, local cache paths); for those the corpus value is
 * representative and the assertion pins presence + JSON type only.
 */
class IpcContractCorpusTest {
    // The non-hydration verbs DaemonRuntime registers inline (see the
    // server.registerHandler calls in DaemonRuntime.start). daemon.status is
    // listed first so its refresh_in_flight:false and idle-enumeration
    // expectations are checked before this test launches a refresh job or an
    // enumeration; daemon.shutdown is listed last
    // because it stops the daemon the other verbs are replayed against.
    private val daemonVerbs = listOf("daemon.status", "sync.subscribe", "refresh.run", "sync.enumerate", "daemon.shutdown")

    @Test
    fun corpus_covers_every_daemon_ipc_verb() {
        val dirUrl = checkNotNull(javaClass.getResource("/ipc-contract")) {
            "ipc-contract corpus directory missing from test resources"
        }
        val files = Files.list(Paths.get(dirUrl.toURI())).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".ndjson") }.toList()
        }
        val expected = (HydrationIpcHandler.VERBS + daemonVerbs).map { "$it.ndjson" }.toSet()
        assertEquals(expected, files.toSet(), "one corpus fixture per registered IPC verb, no extras")

        // IPC authentication: every corpus verb has a verb class (the default deny cannot silently
        // swallow a real verb), and the class table names no verb outside the corpus, i.e. none the
        // daemon does not register.
        assertEquals(
            files.map { it.removeSuffix(".ndjson") }.toSet(),
            IpcAuth.VERB_CLASSES.keys,
            "verb classes (IpcAuth.VERB_CLASSES) must cover exactly the corpus verbs",
        )

        // Corpus self-consistency: every request line in <verb>.ndjson must
        // actually carry that verb.
        for (verb in HydrationIpcHandler.VERBS + daemonVerbs) {
            for ((request, _) in loadPairs(verb)) {
                val req = Json.parseToJsonElement(request)
                assertEquals(
                    JsonPrimitive(verb),
                    (req as JsonObject)["verb"],
                    "request line in $verb.ndjson names a different verb",
                )
            }
        }
    }

    @Test
    fun hydration_verb_replies_match_corpus() = runBlocking {
        val handler = HydrationIpcHandler(ScriptedHydration())
        for (verb in HydrationIpcHandler.VERBS) {
            for ((request, expectedReply) in loadPairs(verb)) {
                val reply = handler.handle(connectionId = "conn-contract", jsonRequest = request)
                assertJsonMatches(
                    expected = Json.parseToJsonElement(expectedReply),
                    actual = Json.parseToJsonElement(reply),
                    at = "$verb reply",
                )
            }
        }
    }

    @Test
    fun daemon_verb_replies_match_corpus_over_live_socket() = runBlocking {
        val tempDir = Files.createTempDirectory("ipc-contract-test")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)

            for (verb in daemonVerbs) {
                for ((request, expectedReply) in loadPairs(verb)) {
                    // Fresh connection per exchange: subscribe-style verbs turn
                    // the connection into an event stream after the reply, so a
                    // shared connection would leak pushed events into the next
                    // verb's reply read. Each one authenticates first (the daemon
                    // writes its tokens next to the lock).
                    val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "contract_profile"), IpcAuth.Scope.FULL)
                    try {
                        channel.configureBlocking(false)
                        channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                        val reply = readFirstLine(channel, timeoutMs = 5_000)
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedReply),
                            actual = Json.parseToJsonElement(reply),
                            at = "$verb reply",
                        )
                    } finally {
                        channel.close()
                    }
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    /**
     * `auth/<name>.ndjson`: one session each, replayed in order on ONE connection against a server
     * holding the fixed test vectors (profile `p1`, token bytes 00..1f for both scopes, server nonce
     * 10..1f). They pin the handshake replies and the auth_required / auth_failed / forbidden shapes.
     */
    @Test
    fun auth_transcripts_replay_against_the_handshake() = runBlocking {
        val dirUrl = checkNotNull(javaClass.getResource("/ipc-contract/auth")) { "ipc-contract/auth missing" }
        val transcripts = Files.list(Paths.get(dirUrl.toURI())).use { s -> s.filter { it.toString().endsWith(".ndjson") }.toList() }
        assertTrue(transcripts.size >= 4, "auth transcripts: $transcripts")
        val token = ByteArray(32) { it.toByte() }
        for (file in transcripts) {
            val pairs = Files.readAllLines(file).filter { it.isNotBlank() }.chunked(2).map { it[0] to it[1] }
            for ((request, _) in pairs) {
                val verb = (Json.parseToJsonElement(request) as JsonObject)["verb"]!!.let { (it as JsonPrimitive).content }
                assertTrue(
                    verb == IpcAuth.HELLO || verb == IpcAuth.HELLO_PROOF || verb in IpcAuth.VERB_CLASSES,
                    "${file.fileName}: '$verb' is neither a handshake verb nor a classified verb",
                )
            }
            val dir = Files.createTempDirectory("ipc-contract-auth")
            val socketPath = dir.resolve("a.sock")
            val auth = IpcAuth("p1", token, token, "contract", serverNonces = { ByteArray(16) { (0x10 + it).toByte() } })
            val server = IpcServer(socketPath, auth = auth)
            val serveJob = kotlinx.coroutines.SupervisorJob()
            server.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + serveJob))
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
                    channel.configureBlocking(false)
                    for ((request, expectedReply) in pairs) {
                        channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
                        assertJsonMatches(
                            expected = Json.parseToJsonElement(expectedReply),
                            actual = Json.parseToJsonElement(readFirstLine(channel, timeoutMs = 5_000)),
                            at = "${file.fileName}: reply to $request",
                        )
                    }
                }
            } finally {
                server.close()
                serveJob.cancel()
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
    }

    @Test
    fun connection_refusal_matches_corpus_over_live_socket() = runBlocking {
        val expected = loadConnectionLine("too_many_clients")
        val tempDir = Files.createTempDirectory("ipc-contract-refusal-test")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        val held = mutableListOf<SocketChannel>()
        try {
            awaitDaemonSocket(socketPath)

            // Each connection is confirmed by a daemon.status reply before the next one opens,
            // so the daemon has counted it; the first connection past the cap reads the refusal
            // line instead of the reply.
            var refusal: String? = null
            for (attempt in 1..64) {
                val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
                held += channel
                channel.configureBlocking(false)
                channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
                val line = readFirstLine(channel, timeoutMs = 5_000)
                val error = (Json.parseToJsonElement(line) as JsonObject)["error"]
                if ((error as? JsonPrimitive)?.content == "too_many_clients") {
                    refusal = line
                    break
                }
            }
            assertJsonMatches(
                expected = Json.parseToJsonElement(expected),
                actual = Json.parseToJsonElement(checkNotNull(refusal) { "no connection was refused" }),
                at = "too_many_clients line",
            )
        } finally {
            held.forEach { runCatching { it.close() } }
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    // ── corpus loading ───────────────────────────────────────────────────────

    private fun loadConnectionLine(token: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/connection/$token.ndjson")) {
            "missing connection-level corpus fixture '$token'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size == 1) { "connection/$token.ndjson must hold exactly one line, got ${lines.size}" }
        return lines.single()
    }

    private fun loadPairs(verb: String): List<Pair<String, String>> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/$verb.ndjson")) {
            "missing corpus fixture for verb '$verb'"
        }
        val lines = stream.bufferedReader().readLines().filter { it.isNotBlank() }
        check(lines.size % 2 == 0) { "$verb.ndjson must hold request/response line pairs, got ${lines.size} lines" }
        return lines.chunked(2).map { it[0] to it[1] }
    }

    // ── parse-and-compare ────────────────────────────────────────────────────

    private fun assertJsonMatches(expected: JsonElement, actual: JsonElement, at: String) {
        when (expected) {
            is JsonNull -> assertEquals(expected, actual, "$at: expected null")
            is JsonObject -> {
                assertTrue(actual is JsonObject, "$at: expected an object, got $actual")
                assertEquals(expected.keys, actual.keys, "$at: key set mismatch")
                for ((key, value) in expected) {
                    val actualValue = actual.getValue(key)
                    if (key in VOLATILE_FIELDS) {
                        assertVolatileShape(value, actualValue, "$at.$key")
                    } else {
                        assertJsonMatches(value, actualValue, "$at.$key")
                    }
                }
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray, "$at: expected an array, got $actual")
                assertEquals(expected.size, actual.size, "$at: array size mismatch")
                expected.forEachIndexed { i, element ->
                    assertJsonMatches(element, actual[i], "$at[$i]")
                }
            }
            is JsonPrimitive -> {
                assertTrue(actual is JsonPrimitive, "$at: expected a primitive, got $actual")
                assertEquals(expected.isString, actual.isString, "$at: string-vs-literal mismatch")
                assertEquals(expected.content, actual.content, "$at: value mismatch")
            }
        }
    }

    // Volatile fields carry run- or machine-dependent values (uuids, uptimes,
    // local cache paths): the corpus value is representative, so pin only
    // presence + JSON type, never the exact bytes.
    private fun assertVolatileShape(expected: JsonElement, actual: JsonElement, at: String) {
        assertTrue(expected is JsonPrimitive && actual is JsonPrimitive, "$at: volatile field must be a primitive")
        assertEquals(expected.isString, actual.isString, "$at: volatile field type mismatch")
    }

    private suspend fun readFirstLine(channel: SocketChannel, timeoutMs: Long): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        val newline = collected.indexOf('\n')
        check(newline >= 0) { "no reply line within ${timeoutMs}ms; collected: $collected" }
        return collected.substring(0, newline)
    }

    /**
     * Deterministic Hydration fake scripted by path convention so the real
     * HydrationIpcHandler emits every reply shape the corpus pins:
     * `/missing...` → the unknown_path / *_not_found family, `/docs/sub` →
     * the it's-a-folder family, `/docs/open.txt` → busy, `/docs/nonempty` →
     * not_empty, `/docs/report.txt` → an existing file, `/outside...` →
     * outside_scope (sync_path guard), `/excluded/...` → accepted with
     * excluded:true (keep-local rule), an open_write cache path outside
     * `/cache/unidrive/hydration` → invalid_path.
     */
    private class ScriptedHydration : Hydration {
        override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult =
            if (path.startsWith("/missing")) OpenResult.Failed(HydrationError.UnknownPath)
            else OpenResult.Ok(cachePathFor(path))

        override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path, baseEtag: String?): OpenResult =
            when {
                // A cache path outside the profile's hydration cache folder is refused before anything else.
                !cachePath.startsWith(cacheDir) -> OpenResult.Failed(HydrationError.InvalidPath)
                path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
                // A stale base_etag refuses the write before any upload runs.
                path == "/docs/report.txt" && baseEtag == "stale-etag" -> OpenResult.Failed(HydrationError.Conflict)
                // keep-local: accepted, never uploaded, flagged on the reply.
                path.startsWith("/excluded/") -> OpenResult.Ok(cachePath, excluded = true)
                path.startsWith("/outside") -> OpenResult.Failed(HydrationError.OutOfScope)
                else -> OpenResult.Ok(cachePath)
            }

        override suspend fun closeHandle(connectionId: String, handleId: String) {}

        override suspend fun cancelUpload(path: String): Boolean = path == "/docs/open.txt"

        override suspend fun hydrate(path: String): HydrateResult =
            if (path.startsWith("/missing")) HydrateResult.Failed(HydrationError.UnknownPath)
            else HydrateResult.Ok

        override suspend fun dehydrate(path: String): DehydrateResult = when {
            path.startsWith("/missing") -> DehydrateResult.Failed(HydrationError.UnknownPath)
            path == "/docs/open.txt" -> DehydrateResult.Busy
            else -> DehydrateResult.Ok
        }

        override suspend fun lastSynced(path: String): LastSyncedResult =
            if (path.startsWith("/missing")) LastSyncedResult.Unknown(HydrationError.UNKNOWN_PATH_TOKEN)
            else LastSyncedResult.Ok(FIXED_MTIME_MS)

        override suspend fun list(prefix: String): ListResult =
            if (prefix == "/docs") {
                ListResult.Ok(
                    listOf(
                        ListResult.Entry(
                            "/docs/report.txt", 42, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-report", etag = "etag-report",
                            pendingUpload = false, hasError = false,
                        ),
                        ListResult.Entry(
                            "/docs/sub", 0, FIXED_MTIME_MS, isHydrated = false, isFolder = true,
                            remoteModifiedEpochMillis = FIXED_REMOTE_MS, remoteId = "rid-sub", etag = null,
                            pendingUpload = false, hasError = false,
                        ),
                        // Written through the mount, upload failed: no remote id / etag /
                        // remote modified time, both flags raised.
                        ListResult.Entry(
                            "/docs/draft.txt", 7, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = null, remoteId = null, etag = null,
                            pendingUpload = true, hasError = true,
                        ),
                        // keep-local: matches exclude_patterns, never uploaded.
                        ListResult.Entry(
                            "/docs/scratch.tmp", 3, FIXED_MTIME_MS, isHydrated = true, isFolder = false,
                            remoteModifiedEpochMillis = null, remoteId = null, etag = null,
                            pendingUpload = false, hasError = false, excluded = true,
                        ),
                    ),
                )
            } else {
                ListResult.Ok(emptyList())
            }

        override suspend fun mkdir(path: String): MkdirResult =
            when {
                path.startsWith("/missing/") -> MkdirResult.ParentNotFound
                path.startsWith("/outside") -> MkdirResult.Failed(HydrationError.OutOfScope)
                else -> MkdirResult.Ok
            }

        override suspend fun unlink(path: String): UnlinkResult =
            when (path) {
                "/docs/sub" -> UnlinkResult.PathIsFolder
                "/docs/uploading.txt" -> UnlinkResult.Busy
                else -> UnlinkResult.Ok
            }

        override suspend fun rmdir(path: String): RmdirResult = when (path) {
            "/docs/report.txt" -> RmdirResult.PathIsFile
            "/docs/nonempty" -> RmdirResult.NotEmpty
            "/docs/uploading" -> RmdirResult.Busy
            else -> RmdirResult.Ok
        }

        override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult = when {
            path.startsWith("/missing/") -> CreateResult.ParentNotFound
            path == "/docs/report.txt" -> CreateResult.PathExists
            // keep-local: the row and cache file exist; the upload never runs.
            path.startsWith("/excluded/") -> CreateResult.Ok(cachePathFor(path), handleId, excluded = true)
            path.startsWith("/outside") -> CreateResult.Failed(HydrationError.OutOfScope)
            else -> CreateResult.Ok(cachePathFor(path), handleId)
        }

        override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult = when {
            path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
            path == "/docs/sub" -> OpenResult.Failed(HydrationError.Generic("path_is_folder"))
            path.startsWith("/excluded/") -> OpenResult.Ok(cachePathFor(path), excluded = true)
            path.startsWith("/outside") -> OpenResult.Failed(HydrationError.OutOfScope)
            else -> OpenResult.Ok(cachePathFor(path))
        }

        override suspend fun rename(oldPath: String, newPath: String, replace: Boolean): RenameResult = when {
            oldPath.startsWith("/missing") -> RenameResult.OldPathNotFound
            newPath.startsWith("/missing/") -> RenameResult.NewParentNotFound
            // Without replace an existing destination refuses; with replace the
            // scripted fake succeeds (the delete-then-move sequence is opaque here).
            newPath == "/docs/sub" && !replace -> RenameResult.NewPathExists
            oldPath.startsWith("/outside") || newPath.startsWith("/outside") -> RenameResult.Failed(HydrationError.OutOfScope)
            newPath.startsWith("/excluded/") -> RenameResult.Failed(HydrationError.Excluded)
            else -> RenameResult.Ok
        }

        override val events: Flow<HydrationEvent> = emptyFlow()

        override fun onConnectionClosed(connectionId: String) {}

        private fun cachePathFor(path: String): Path = Paths.get("/cache/unidrive/hydration$path")

        private val cacheDir: Path = Paths.get("/cache/unidrive/hydration")
    }

    /**
     * Minimal CloudProvider stub (mirrors DaemonRuntimeTest's): only
     * authenticateAndLog() plus an empty delta run during refresh.run /
     * sync.enumerate are exercised here.
     */
    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    companion object {
        private val VOLATILE_FIELDS = setOf("cache_path", "uptime_ms", "clients_connected", "job_id", "engine_version")
        private const val FIXED_MTIME_MS = 1234567890123L
        private const val FIXED_REMOTE_MS = 1234567890000L
    }
}
