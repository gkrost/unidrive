package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.cli.LegacyInventory.CloudMatch
import org.krost.unidrive.cli.LegacyInventory.PathClass
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #560 U5a: the read-only inventory of a legacy profile. Fixture profiles in temp dirs (profile dir with state.db,
 * sync_root, hydration cache, client recovery dir); every test that runs the inventory also checks that no byte of
 * the fixture changed.
 */
class LegacyInventoryTest {
    private lateinit var root: Path
    private lateinit var profileDir: Path
    private lateinit var syncRoot: Path
    private lateinit var cacheDir: Path
    private lateinit var clientDir: Path
    private lateinit var tempRoot: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("unidrive-inventory-test-")
        profileDir = Files.createDirectories(root.resolve("profile"))
        syncRoot = Files.createDirectories(root.resolve("sync"))
        cacheDir = Files.createDirectories(root.resolve("cache"))
        clientDir = Files.createDirectories(root.resolve("client"))
        tempRoot = Files.createDirectories(root.resolve("tmp"))
    }

    @AfterTest
    fun tearDown() {
        runCatching { root.toFile().deleteRecursively() }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun seed(block: StateDatabase.() -> Unit) {
        val db = StateDatabase(profileDir.resolve("state.db"))
        try {
            db.initialize()
            db.block()
        } finally {
            db.close()
        }
    }

    private fun row(
        path: String,
        remoteId: String?,
        hydrated: Boolean = true,
        localHash: String? = null,
        remoteHash: String? = null,
        cacheBacked: Boolean? = null,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = remoteHash,
        remoteSize = 0,
        remoteModified = null,
        localMtime = 1L,
        localSize = 1L,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.parse("2026-10-01T00:00:00Z"),
        localHash = localHash,
        cacheBacked = cacheBacked,
    )

    private fun put(
        dir: Path,
        path: String,
        content: String,
    ) {
        val f = dir.resolve(path.trimStart('/'))
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    private fun sha(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun inputs(
        providerType: String = "internxt",
        excludes: List<String> = emptyList(),
        syncPaths: List<String> = emptyList(),
    ) = LegacyInventory.Inputs(
        profileName = "legacy",
        providerType = providerType,
        profileDir = profileDir,
        syncRoot = syncRoot,
        cacheDir = cacheDir,
        syncPaths = syncPaths,
        excludePatterns = excludes,
        configFacts = listOf("type" to providerType),
        clientStateDir = clientDir,
        tempRoot = tempRoot,
    )

    /**
     * Path → SHA-256 of every file under the fixture (except the inventory's temp root), plus every directory. A lock
     * file held by a writer (Windows refuses to read it) is recorded by its size.
     */
    private fun treeDigest(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        Files.walk(root).use { s ->
            s.filter { !it.startsWith(tempRoot) }.forEach { p ->
                val key = root.relativize(p).toString().replace('\\', '/')
                out[key] =
                    if (Files.isDirectory(p)) {
                        "dir"
                    } else {
                        runCatching { MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) } }
                            .getOrElse { "unreadable, size ${Files.size(p)}" }
                    }
            }
        }
        return out
    }

    /** Runs the inventory and asserts it changed nothing and left no temp file behind. */
    private fun inventory(inputs: LegacyInventory.Inputs = inputs()): LegacyInventory.Report {
        val before = treeDigest()
        val outcome = LegacyInventory.collect(inputs)
        assertEquals(before, treeDigest(), "the inventory must not change a byte of the profile")
        assertEquals(emptyList(), Files.list(tempRoot).use { it.toList() }, "the state.db snapshot must be removed")
        return assertIs<LegacyInventory.Outcome.Collected>(outcome).report
    }

    // ── classification ───────────────────────────────────────────────────────

    @Test
    fun `every local location class is told apart`() {
        seed {
            upsertEntry(row("/clean.txt", "r-clean", localHash = sha("clean"), cacheBacked = false))
            upsertEntry(row("/root-only.txt", "r-root", localHash = sha("root"), cacheBacked = false))
            upsertEntry(row("/cache-only.txt", "r-cache", remoteHash = "version-token", cacheBacked = true))
            upsertEntry(row("/differs.txt", "r-diff", localHash = sha("old"), cacheBacked = false))
            upsertEntry(row("/cloud-only.txt", "r-cloud", hydrated = false))
        }
        put(syncRoot, "/clean.txt", "clean")
        put(cacheDir, "/clean.txt", "clean")
        put(syncRoot, "/root-only.txt", "root")
        put(cacheDir, "/cache-only.txt", "cache")
        put(syncRoot, "/differs.txt", "new-a")
        put(cacheDir, "/differs.txt", "new-b")
        put(syncRoot, "/untracked.txt", "u")
        put(syncRoot, "/sub/keep.bak", "k")

        val report = inventory(inputs(excludes = listOf("**/*.bak")))

        assertEquals(PathClass.BOTH_IDENTICAL, report.record("/clean.txt").cls)
        assertEquals(CloudMatch.SAME_RECORDED_HASH, report.record("/clean.txt").syncRootMatch)
        assertEquals(emptyList(), report.record("/clean.txt").risks)

        assertEquals(PathClass.SYNC_ROOT_ONLY, report.record("/root-only.txt").cls)
        assertEquals(emptyList(), report.record("/root-only.txt").risks)

        val cacheOnly = report.record("/cache-only.txt")
        assertEquals(PathClass.CACHE_ONLY, cacheOnly.cls)
        assertEquals(CloudMatch.UNKNOWN, cacheOnly.cacheMatch)
        assertTrue(cacheOnly.risks.isNotEmpty(), "an unproven cache-only copy is at risk")

        val differs = report.record("/differs.txt")
        assertEquals(PathClass.BOTH_DIFFERENT, differs.cls)
        assertEquals(CloudMatch.DIFFERS, differs.syncRootMatch)
        assertEquals(CloudMatch.DIFFERS, differs.cacheMatch)
        assertTrue(differs.risks.isNotEmpty())

        val untracked = report.record("/untracked.txt")
        assertEquals(PathClass.UNTRACKED, untracked.cls)
        assertFalse(untracked.excluded)
        assertTrue(untracked.risks.isNotEmpty(), "a file that never reached the cloud is at risk")

        val excluded = report.record("/sub/keep.bak")
        assertEquals(PathClass.UNTRACKED, excluded.cls)
        assertTrue(excluded.excluded)
        assertTrue(excluded.risks.isNotEmpty(), "an excluded keep-local file exists nowhere else")

        assertEquals(1, report.classCounts[PathClass.NO_LOCAL_COPY], "the cloud-only row is counted")
        assertEquals(
            setOf("/cache-only.txt", "/differs.txt", "/untracked.txt", "/sub/keep.bak"),
            report.atRisk.map { it.path }.toSet(),
        )
    }

    @Test
    fun `an Internxt remote_hash is a version token and never proves a copy clean`() {
        // The token equals the copy's SHA-256 here on purpose: comparing against it would wrongly say "same".
        seed { upsertEntry(row("/a.txt", "r-a", remoteHash = sha("bytes"))) }
        put(syncRoot, "/a.txt", "bytes")

        val rec = inventory().record("/a.txt")

        assertEquals(CloudMatch.UNKNOWN, rec.syncRootMatch)
        assertTrue(rec.risks.isNotEmpty())
    }

    @Test
    fun `OneDrive copies are compared by quickXor`() {
        // QuickXorHash of zero bytes: 20 zero bytes, Base64.
        val emptyQuickXor = "AAAAAAAAAAAAAAAAAAAAAAAAAAA="
        seed {
            upsertEntry(row("/empty.txt", "r-e", remoteHash = emptyQuickXor))
            upsertEntry(row("/edited.txt", "r-x", remoteHash = emptyQuickXor))
        }
        put(syncRoot, "/empty.txt", "")
        put(syncRoot, "/edited.txt", "now with bytes")

        val report = inventory(inputs(providerType = "onedrive"))

        assertEquals(CloudMatch.SAME_CONTENT_HASH, report.record("/empty.txt").syncRootMatch)
        assertEquals(emptyList(), report.record("/empty.txt").risks)
        assertEquals(CloudMatch.DIFFERS, report.record("/edited.txt").syncRootMatch)
    }

    @Test
    fun `pending uploads are reported under both predicates`() {
        seed {
            // Written through the mount: cache copy, cache_backed.
            upsertEntry(row("/mount-new.txt", null, cacheBacked = true))
            // Written by LocalScanner: content in the sync_root.
            upsertEntry(row("/mirror-new.txt", null))
            // A sparse leftover LocalScanner did not claim as hydrated.
            upsertEntry(row("/sparse.txt", null, hydrated = false))
            // Pending, but its bytes are gone from both places.
            upsertEntry(row("/lost.txt", null, cacheBacked = true))
        }
        put(cacheDir, "/mount-new.txt", "m")
        put(syncRoot, "/mirror-new.txt", "s")
        put(syncRoot, "/sparse.txt", "s")

        val report = inventory()
        val db = assertNotNull(report.db)

        assertEquals(listOf("/lost.txt", "/mirror-new.txt", "/mount-new.txt"), db.pendingUploads)
        assertEquals(listOf("/mount-new.txt"), db.pendingMountReplayable)
        assertEquals(listOf("/mirror-new.txt"), db.pendingMirror)
        assertEquals(listOf("/lost.txt"), db.pendingWithoutBytes)
        assertEquals(listOf("/sparse.txt"), db.localUnhydrated)
        assertEquals(4, db.localIdRows)
        for (p in listOf("/mount-new.txt", "/mirror-new.txt", "/sparse.txt", "/lost.txt")) {
            assertTrue(report.record(p).risks.isNotEmpty(), "$p must be at risk")
        }
    }

    @Test
    fun `refused and failed uploads are reported`() {
        seed {
            upsertEntry(row("/refused.txt", null, cacheBacked = true))
            markUploadRefused("/refused.txt", "1|1|too large")
            upsertEntry(row("/failed.txt", null, cacheBacked = true))
            markUploadFailed("/failed.txt", Instant.parse("2026-10-02T00:00:00Z"))
        }
        put(cacheDir, "/refused.txt", "r")
        put(cacheDir, "/failed.txt", "f")

        val report = inventory()
        val db = assertNotNull(report.db)

        assertEquals(listOf("/refused.txt"), db.uploadRefused)
        assertEquals(listOf("/failed.txt"), db.lastErrorAt)
        assertTrue(report.record("/refused.txt").risks.any { "refused" in it })
        assertTrue(report.record("/failed.txt").risks.any { "failed" in it })
    }

    @Test
    fun `tombstones are counted and a leftover file is flagged`() {
        seed {
            upsertEntry(row("/gone.txt", "r-gone", localHash = sha("g")))
            markDeleted("/gone.txt")
        }
        put(syncRoot, "/gone.txt", "g")

        val report = inventory()

        assertEquals(1, assertNotNull(report.db).tombstones)
        val rec = report.record("/gone.txt")
        assertEquals(PathClass.UNTRACKED, rec.cls)
        assertTrue(rec.tombstoned)
        assertTrue(rec.risks.isNotEmpty())
    }

    @Test
    fun `the cache_backed distribution covers true, false and null`() {
        seed {
            upsertEntry(row("/t1", "r1", cacheBacked = true))
            upsertEntry(row("/t2", "r2", cacheBacked = true))
            upsertEntry(row("/f1", "r3", cacheBacked = false))
            upsertEntry(row("/n1", "r4", cacheBacked = null))
        }

        val dist = assertNotNull(inventory().db).cacheBacked

        assertEquals(mapOf("true" to 2, "false" to 1, "null" to 1), dist)
    }

    @Test
    fun `upload staging and client recovery files are listed by size, never by content`() {
        seed { }
        put(profileDir, "/upload-tombstones/abc.json", "{\"fileKey\":\"secret-key-material\"}")
        put(profileDir, "/upload-tombstones/abc.enc", "0123456789")
        put(cacheDir, "/dir/.f.txt.hydrating-1", "xx")
        put(clientDir, "/legacy.uploads/one", "12345")
        put(clientDir, "/legacy.journal", "abc")
        put(clientDir, "/other.journal", "not this profile")

        val report = inventory()

        assertEquals(2, report.staging.uploadTombstoneFiles)
        assertEquals(10L + "{\"fileKey\":\"secret-key-material\"}".length, report.staging.uploadTombstoneBytes)
        assertEquals(1, report.staging.cacheTempFiles)
        assertEquals(
            listOf("legacy.journal" to 3L, "legacy.uploads" to 5L),
            report.clientFiles.map { it.name to it.bytes },
        )
        assertFalse(report.files.any { it.path.contains("hydrating") }, "a staging temp is not a cache copy")
        val text = MigrateInventoryCommand.renderText(report, detail = true)
        val json = MigrateInventoryCommand.renderJson(report)
        assertFalse("secret-key-material" in text)
        assertFalse("secret-key-material" in json)
    }

    // ── read-only contract ───────────────────────────────────────────────────

    @Test
    fun `a held profile lock refuses the run before anything is read`() {
        seed { upsertEntry(row("/a.txt", "r-a")) }
        val daemon = ProcessLock(profileDir.resolve(".lock"))
        try {
            assertTrue(daemon.tryLock(ProcessLock.Mode.DAEMON))
            val before = treeDigest()

            val outcome = LegacyInventory.collect(inputs())

            val refused = assertIs<LegacyInventory.Outcome.Refused>(outcome)
            assertTrue("daemon stop" in refused.message, refused.message)
            assertTrue("legacy" in refused.message, refused.message)
            assertEquals(before, treeDigest())
            assertEquals(emptyList(), Files.list(tempRoot).use { it.toList() }, "no snapshot is taken")
        } finally {
            daemon.unlock()
        }
    }

    @Test
    fun `a free lock file and the database stay byte-identical and the report says nothing changed`() {
        seed {
            upsertEntry(row("/a.txt", "r-a", localHash = sha("a")))
            upsertEntry(row("/p.txt", null, cacheBacked = true))
            setSyncState("cursor", "c-1")
        }
        Files.writeString(profileDir.resolve(".lock"), "")
        put(syncRoot, "/a.txt", "a")
        put(cacheDir, "/p.txt", "p")

        val report = inventory()

        val json = Json.parseToJsonElement(MigrateInventoryCommand.renderJson(report)).jsonObject
        val summary = assertNotNull(json["summary"]).jsonObject
        assertTrue(assertNotNull(summary["nothing_changed"]).jsonPrimitive.boolean)
        assertTrue("Nothing was changed" in MigrateInventoryCommand.renderText(report, detail = false))
    }

    @Test
    fun `a profile without state db still inventories its local files`() {
        put(syncRoot, "/only-local.txt", "x")

        val report = inventory()

        assertEquals(null, report.db)
        assertEquals(PathClass.UNTRACKED, report.record("/only-local.txt").cls)
    }

    @Test
    fun `a sync_root that lies inside the temp dir is still walked`() {
        put(syncRoot, "/inside-temp.txt", "x")

        val outcome = LegacyInventory.collect(inputs().copy(tempRoot = root))

        val report = assertIs<LegacyInventory.Outcome.Collected>(outcome).report
        assertEquals(PathClass.UNTRACKED, report.record("/inside-temp.txt").cls)
    }

    @Test
    fun `migrate inventory is registered`() {
        val migrate = assertNotNull(CommandLine(Main()).subcommands["migrate"])
        assertTrue("inventory" in migrate.subcommands.keys)
    }
}
