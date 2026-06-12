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
 * Comparison is parse-and-compare (key-order-insensitive), never strcmp.
 * A small set of VOLATILE_FIELDS carry machine- or run-dependent values
 * (uuids, uptimes, local cache paths); for those the corpus value is
 * representative and the assertion pins presence + JSON type only.
 */
class IpcContractCorpusTest {
    // The four non-hydration verbs DaemonRuntime registers inline (see the
    // server.registerHandler calls in DaemonRuntime.start). daemon.status is
    // listed first so its refresh_in_flight:false expectation is checked
    // before this test launches a refresh job.
    private val daemonVerbs = listOf("daemon.status", "sync.subscribe", "refresh.run", "sync.enumerate")

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
            profileName = "contract_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        try {
            repeat(50) {
                if (Files.exists(socketPath)) return@repeat
                delay(50)
            }
            assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

            for (verb in daemonVerbs) {
                for ((request, expectedReply) in loadPairs(verb)) {
                    // Fresh connection per exchange: subscribe-style verbs turn
                    // the connection into an event stream after the reply, so a
                    // shared connection would leak pushed events into the next
                    // verb's reply read.
                    val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
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

    // ── corpus loading ───────────────────────────────────────────────────────

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
     * not_empty, `/docs/report.txt` → an existing file.
     */
    private class ScriptedHydration : Hydration {
        override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult =
            if (path.startsWith("/missing")) OpenResult.Failed(HydrationError.UnknownPath)
            else OpenResult.Ok(cachePathFor(path))

        override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult =
            if (path.startsWith("/missing")) OpenResult.Failed(HydrationError.UnknownPath)
            else OpenResult.Ok(cachePath)

        override suspend fun closeHandle(connectionId: String, handleId: String) {}

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
                        ListResult.Entry("/docs/report.txt", 42, FIXED_MTIME_MS, isHydrated = true, isFolder = false),
                        ListResult.Entry("/docs/sub", 0, FIXED_MTIME_MS, isHydrated = false, isFolder = true),
                    ),
                )
            } else {
                ListResult.Ok(emptyList())
            }

        override suspend fun mkdir(path: String): MkdirResult =
            if (path.startsWith("/missing/")) MkdirResult.ParentNotFound else MkdirResult.Ok

        override suspend fun unlink(path: String): UnlinkResult =
            if (path == "/docs/sub") UnlinkResult.PathIsFolder else UnlinkResult.Ok

        override suspend fun rmdir(path: String): RmdirResult = when (path) {
            "/docs/report.txt" -> RmdirResult.PathIsFile
            "/docs/nonempty" -> RmdirResult.NotEmpty
            else -> RmdirResult.Ok
        }

        override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult = when {
            path.startsWith("/missing/") -> CreateResult.ParentNotFound
            path == "/docs/report.txt" -> CreateResult.PathExists
            else -> CreateResult.Ok(cachePathFor(path), handleId)
        }

        override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult = when {
            path.startsWith("/missing") -> OpenResult.Failed(HydrationError.UnknownPath)
            path == "/docs/sub" -> OpenResult.Failed(HydrationError.Generic("path_is_folder"))
            else -> OpenResult.Ok(cachePathFor(path))
        }

        override suspend fun rename(oldPath: String, newPath: String): RenameResult = when {
            oldPath.startsWith("/missing") -> RenameResult.OldPathNotFound
            newPath.startsWith("/missing/") -> RenameResult.NewParentNotFound
            newPath == "/docs/sub" -> RenameResult.NewPathExists
            else -> RenameResult.Ok
        }

        override val events: Flow<HydrationEvent> = emptyFlow()

        override fun onConnectionClosed(connectionId: String) {}

        private fun cachePathFor(path: String): Path = Paths.get("/cache/unidrive/hydration$path")
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
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String) = error("not used")

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
        private val VOLATILE_FIELDS = setOf("cache_path", "uptime_ms", "clients_connected", "job_id")
        private const val FIXED_MTIME_MS = 1234567890123L
    }
}
