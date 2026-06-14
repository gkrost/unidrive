package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end-to-end tests run the real command through Main's picocli CommandLine
 * against a localfs profile: the "cloud" is a second temp directory, so every
 * divergence class can be seeded by mutating one of the three sources (local
 * tree, state.db, remote dir) behind the others' backs. The pure classification
 * edge cases live in the VerifyAudit tests below them.
 */
class VerifyCommandTest {
    private lateinit var configDir: Path
    private lateinit var syncRoot: Path
    private lateinit var remoteRoot: Path
    private lateinit var dbPath: Path
    private val originalOut = System.out
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-verify-cfg")
        syncRoot = Files.createTempDirectory("ud-verify-local")
        remoteRoot = Files.createTempDirectory("ud-verify-remote")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |sync_root = "${toml(syncRoot)}"
            |root_path = "${toml(remoteRoot)}"
            """.trimMargin(),
        )
        dbPath =
            configDir
                .resolve("localtest")
                .also { Files.createDirectories(it) }
                .resolve("state.db")
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun runVerify(vararg extra: String): Int =
        CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "verify", *extra)

    private fun output(): String = captured.toString(Charsets.UTF_8)

    private fun entry(
        path: String,
        size: Long,
        isHydrated: Boolean = true,
        isFolder: Boolean = false,
    ): SyncEntry =
        SyncEntry(
            path = path,
            remoteId = path,
            remoteHash = null,
            remoteSize = size,
            remoteModified = Instant.now(),
            localMtime = if (isHydrated) Instant.now().toEpochMilli() else null,
            localSize = if (isHydrated) size else null,
            isFolder = isFolder,
            isPinned = false,
            isHydrated = isHydrated,
            lastSynced = Instant.now(),
        )

    private fun withDb(block: (StateDatabase) -> Unit) {
        val db = StateDatabase(dbPath)
        db.initialize()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    /** Seed one converged file: same bytes locally and remotely, hydrated DB row. */
    private fun seedConverged(
        rel: String,
        content: String,
    ) {
        val bytes = content.toByteArray()
        val local = syncRoot.resolve(rel)
        Files.createDirectories(local.parent ?: syncRoot)
        Files.write(local, bytes)
        val remote = remoteRoot.resolve(rel)
        Files.createDirectories(remote.parent ?: remoteRoot)
        Files.write(remote, bytes)
        withDb { it.upsertEntry(entry("/$rel", bytes.size.toLong())) }
    }

    // ── end-to-end: exit codes + classification through the real CLI ──────────

    @Test
    fun `converged profile exits 0`() {
        seedConverged("a.txt", "alpha")
        seedConverged("dir/b.txt", "bravo")
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify())
        assertTrue(output().contains("converged"), "expected converged result line, got:\n${output()}")
    }

    @Test
    fun `local file deleted behind the DB's back is remote-only plus broken hydration claim`() {
        seedConverged("a.txt", "alpha")
        seedConverged("gone.txt", "i will be deleted locally")
        Files.delete(syncRoot.resolve("gone.txt"))
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify())
        val out = output()
        assertTrue(out.contains("remote-only") && out.contains("/gone.txt"), "expected remote-only /gone.txt, got:\n$out")
        assertTrue(out.contains("hydration-claim"), "isHydrated=true over a missing local file must be flagged, got:\n$out")
    }

    @Test
    fun `extra untracked local file is local-only`() {
        seedConverged("a.txt", "alpha")
        Files.writeString(syncRoot.resolve("extra.txt"), "never synced")
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify())
        assertTrue(output().contains("local-only") && output().contains("/extra.txt"), "got:\n${output()}")
    }

    @Test
    fun `orphan DB row with neither local nor remote file is row-only`() {
        seedConverged("a.txt", "alpha")
        withDb { it.upsertEntry(entry("/orphan.txt", 42)) }
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify())
        val out = output()
        assertTrue(out.contains("row-only") && out.contains("/orphan.txt"), "got:\n$out")
    }

    @Test
    fun `hydrated claim over a zero-byte local file is flagged`() {
        seedConverged("a.txt", "alpha")
        seedConverged("fake.txt", "real content")
        // Truncate the local copy behind the DB's back — the row still claims isHydrated=true.
        Files.write(syncRoot.resolve("fake.txt"), ByteArray(0))
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify())
        assertTrue(output().contains("hydration-claim") && output().contains("/fake.txt"), "got:\n${output()}")
    }

    @Test
    fun `local content of different size than remote is content-mismatch`() {
        seedConverged("a.txt", "alpha")
        seedConverged("drift.txt", "original content")
        Files.writeString(syncRoot.resolve("drift.txt"), "locally edited and longer than before")
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify())
        assertTrue(output().contains("content-mismatch") && output().contains("/drift.txt"), "got:\n${output()}")
    }

    @Test
    fun `default-excluded junk does not diverge`() {
        seedConverged("a.txt", "alpha")
        // All three are in DEFAULT_EXCLUDE_PATTERNS: engine-internal sidecars + OS junk.
        Files.createDirectories(syncRoot.resolve(".unidrive-trash"))
        Files.writeString(syncRoot.resolve(".unidrive-trash/old.bin"), "trashed")
        Files.writeString(syncRoot.resolve("Thumbs.db"), "windows junk")
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify())
    }

    @Test
    fun `missing state db cannot audit and exits 2`() {
        Files.writeString(syncRoot.resolve("a.txt"), "alpha")
        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, runVerify())
    }

    @Test
    fun `missing sync root cannot audit and exits 2`() {
        seedConverged("a.txt", "alpha")
        Files.delete(syncRoot.resolve("a.txt"))
        Files.delete(syncRoot)
        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, runVerify())
    }

    @Test
    fun `deep flag is accepted and a converged localfs profile still exits 0`() {
        // localfs has no hash algorithm, so --deep must degrade to the shallow
        // size comparison instead of flagging false mismatches.
        seedConverged("a.txt", "alpha")
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify("--deep"))
    }

    @Test
    fun `verify command is registered with --deep option`() {
        val verifyCmd = CommandLine(Main()).subcommands["verify"]
        assertNotNull(verifyCmd, "verify subcommand should be registered")
        val options = verifyCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--deep" in options)
    }
}

/** Pure classification edge cases that the end-to-end seeds above don't reach. */
class VerifyAuditTest {
    private fun entry(
        path: String,
        remoteSize: Long,
        isHydrated: Boolean = true,
        isFolder: Boolean = false,
    ): SyncEntry =
        SyncEntry(
            path = path,
            remoteId = path,
            remoteHash = null,
            remoteSize = remoteSize,
            remoteModified = Instant.now(),
            localMtime = 0L,
            localSize = remoteSize,
            isFolder = isFolder,
            isPinned = false,
            isHydrated = isHydrated,
            lastSynced = Instant.now(),
        )

    @Test
    fun `all sources empty is converged`() {
        val report = VerifyAudit.audit(emptyMap(), emptyList(), emptyMap())
        assertTrue(report.converged)
    }

    @Test
    fun `non-hydrated placeholder row is not consulted for a deep hash mismatch`() {
        // A sparse placeholder's content is NULs by design — only rows claiming
        // isHydrated=true (or untracked path pairs) may fail the hash probe.
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/sparse.bin" to 100L),
                dbEntries = listOf(entry("/sparse.bin", 100L, isHydrated = false)),
                remoteFiles = mapOf("/sparse.bin" to 100L),
                hashMismatch = { true },
            )
        assertTrue(report.contentMismatch.isEmpty(), "placeholder must not be a content mismatch: $report")
    }

    @Test
    fun `non-hydrated placeholder smaller on disk than remote is not a content mismatch`() {
        // The real sparse case: the placeholder is a 0-byte stub on disk while
        // the remote file is 100 bytes. The size difference is by design and must
        // not be reported — otherwise verify floods with false positives on every
        // file-on-demand profile (exactly the soak target).
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/sparse.bin" to 0L),
                dbEntries = listOf(entry("/sparse.bin", 100L, isHydrated = false)),
                remoteFiles = mapOf("/sparse.bin" to 100L),
            )
        assertTrue(report.converged, "0-byte placeholder of a 100-byte remote must not diverge: $report")
    }

    @Test
    fun `hydrated row failing the deep hash probe is a content mismatch`() {
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/edited.bin" to 100L),
                dbEntries = listOf(entry("/edited.bin", 100L)),
                remoteFiles = mapOf("/edited.bin" to 100L),
                hashMismatch = { true },
            )
        assertEquals(listOf("/edited.bin"), report.contentMismatch)
    }

    @Test
    fun `untracked path pair failing the deep hash probe is a content mismatch`() {
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/untracked.bin" to 100L),
                dbEntries = emptyList(),
                remoteFiles = mapOf("/untracked.bin" to 100L),
                hashMismatch = { true },
            )
        assertEquals(listOf("/untracked.bin"), report.contentMismatch)
    }

    @Test
    fun `genuinely empty remote file hydrated as zero bytes is not a hydration mismatch`() {
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/empty.txt" to 0L),
                dbEntries = listOf(entry("/empty.txt", 0L)),
                remoteFiles = mapOf("/empty.txt" to 0L),
            )
        assertTrue(report.converged, "0-byte remote hydrated as 0 bytes is fine: $report")
    }

    @Test
    fun `folder rows are ignored`() {
        val report =
            VerifyAudit.audit(
                localFiles = emptyMap(),
                dbEntries = listOf(entry("/some-dir", 0L, isFolder = true)),
                remoteFiles = emptyMap(),
            )
        assertTrue(report.converged, "folder rows are structural, not auditable content: $report")
    }

    @Test
    fun `pending upload row with local file is local-only but not a hydration mismatch`() {
        // LocalScanner pre-writes NEW files as isHydrated=true with remoteSize=0
        // before the upload lands; the local bytes exist, so only the missing
        // remote copy diverges.
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/pending.txt" to 7L),
                dbEntries = listOf(entry("/pending.txt", 0L)),
                remoteFiles = emptyMap(),
            )
        assertEquals(listOf("/pending.txt"), report.localOnly)
        assertTrue(report.hydrationMismatch.isEmpty(), "local bytes exist — hydration claim holds: $report")
    }

    @Test
    fun `each divergence path is reported in sorted order`() {
        val report =
            VerifyAudit.audit(
                localFiles = mapOf("/z.txt" to 1L, "/a.txt" to 1L),
                dbEntries = emptyList(),
                remoteFiles = emptyMap(),
            )
        assertEquals(listOf("/a.txt", "/z.txt"), report.localOnly)
    }
}
