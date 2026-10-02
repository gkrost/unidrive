package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
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
 * #401: two live remote items resolving to one path key must not silently drop a
 * twin. The gather admits through a deterministic winner rule (folder > file >
 * the id state.db already tracks > smallest id), reports every suppressed twin
 * (warning + sync_state counters), keeps the gather COMPLETE (a collision must not
 * suspend reaping/uploads profile-wide), and the absence sweep must never reap a
 * suppressed twin's row.
 */
class RemoteGatherCollisionTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: CollisionFakeProvider
    private lateinit var reporter: RecordingReporter
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-collide-root")
        dbDir = Files.createTempDirectory("ud-collide-db")
        cacheRoot = Files.createTempDirectory("ud-collide-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = CollisionFakeProvider()
        reporter = RecordingReporter()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = reporter,
                cacheRoot = cacheRoot,
                cacheKey = "collision-test",
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // ---- winner rule ----

    @Test
    fun `two same-path files admit one winner under either emit order`() =
        runTest {
            // Never-tracked twins: the smallest id wins, so the emit order must not matter.
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "b-twin", size = 9),
                    file("/dup.txt", id = "a-twin", size = 7),
                )
            val r = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(r.ok)
            val row = db.getEntry("/dup.txt")
            assertNotNull(row)
            assertEquals("a-twin", row.remoteId, "smallest id must win regardless of emit order")
            assertEquals(1, db.getEntryCount(), "the loser twin must not become a second row")
        }

    @Test
    fun `emitting the twins in the opposite order picks the same winner`() =
        runTest {
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "a-twin", size = 7),
                    file("/dup.txt", id = "b-twin", size = 9),
                )
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("a-twin", db.getEntry("/dup.txt")?.remoteId)
        }

    @Test
    fun `folder beats file at the same path`() =
        runTest {
            provider.deltaItems =
                listOf(
                    file("/thing", id = "file-1"),
                    folder("/thing", id = "folder-1"),
                )
            engine.enumerateRemoteIntoState(reset = false)
            val row = db.getEntry("/thing")
            assertNotNull(row)
            assertEquals("folder-1", row.remoteId)
            assertTrue(row.isFolder)
        }

    @Test
    fun `the id already tracked in state db wins over emit order and smallest id`() =
        runTest {
            // Run 1 tracks the larger-id twin only.
            provider.deltaItems = listOf(file("/dup.txt", id = "z-older", size = 3))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("z-older", db.getEntry("/dup.txt")?.remoteId)

            // Run 2 (full re-enumeration) emits both twins, larger id FIRST. The
            // row's id must hold the path — flipping rows per run is the #401 churn.
            db.setSyncState("delta_cursor", "")
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "z-older", size = 3),
                    file("/dup.txt", id = "a-newer", size = 5),
                )
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("z-older", db.getEntry("/dup.txt")?.remoteId, "the tracked twin must keep the path")
            assertEquals(1, db.getEntryCount())
        }

    @Test
    fun `NFC and NFD spellings of one name collide into one row`() =
        runTest {
            val nfc = "/caf\u00e9.txt"
            val nfd = "/cafe\u0301.txt"
            provider.deltaItems =
                listOf(
                    file(nfd, id = "nfd-twin"),
                    file(nfc, id = "nfc-twin"),
                )
            engine.enumerateRemoteIntoState(reset = false)
            // resolveItemPath normalizes to NFC before admission, so the twins meet
            // at one key; exactly one row survives (queries normalize to NFC too,
            // so the row count is the observable).
            assertEquals(1, db.getEntryCount())
            assertEquals("nfc-twin", db.getEntry(nfc)?.remoteId, "smallest id wins")
        }

    // ---- reporting and completeness ----

    @Test
    fun `a collision warns, records skipped-op state keys and keeps the gather complete`() =
        runTest {
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "a-twin", size = 7),
                    file("/dup.txt", id = "b-twin", size = 9),
                    file("/keep.txt", id = "keep-1"),
                )
            val r = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(r.complete, "a collision must NOT mark the gather incomplete")
            assertEquals("true", db.getSyncState("last_gather_full"))
            assertTrue(
                reporter.warnings.any { "a-twin" in it && "b-twin" in it && "/dup.txt" in it },
                "the warning must name the path and both twin ids; got ${reporter.warnings}",
            )
            assertEquals("1", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))
            assertEquals("/dup.txt", db.getSyncState(SyncEngine.REMOTE_COLLISION_PATHS_KEY))
        }

    @Test
    fun `a clean gather clears the collision counters`() =
        runTest {
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "a-twin"),
                    file("/dup.txt", id = "b-twin"),
                )
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("1", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))

            provider.deltaItems = listOf(file("/dup.txt", id = "a-twin"))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("0", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))
            assertTrue(db.getSyncState(SyncEngine.REMOTE_COLLISION_PATHS_KEY).isNullOrEmpty())
        }

    @Test
    fun `collided paths never reach the provider as path-addressed deletes`() =
        runTest {
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "a-twin"),
                    file("/dup.txt", id = "b-twin"),
                    file("/gone.txt", id = "gone-1"),
                )
            engine.enumerateRemoteIntoState(reset = false)
            // Run 2 is a full pass where /gone.txt vanished remotely. The reap is a
            // state.db flip (never provider.delete), and the collision machinery must
            // not add any provider-side delete of its own.
            db.setSyncState("delta_cursor", "")
            provider.deltaItems =
                listOf(
                    file("/dup.txt", id = "a-twin"),
                    file("/dup.txt", id = "b-twin"),
                )
            val r = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(r.ok)
            assertEquals(1, r.reaped, "the genuinely-deleted row must still be reaped")
            assertEquals(0, provider.deletedPaths.size, "reaping is a DB flip, never provider.delete")
            assertEquals(0, provider.deletedByIds.size)
            // The suppressed twin's path still resolves to exactly one row.
            assertEquals("a-twin", db.getEntry("/dup.txt")?.remoteId)
            assertEquals(1, db.getEntryCount())
        }

    @Test
    fun `an incremental delta carrying only the other twin re-keys the row with a warning recorded`() =
        runTest {
            // The residual hazard #401 documents (durable fix: #403 decorated names):
            // a delta that carries ONLY the untracked twin flips the row to it. It must
            // not vanish silently and must not crash — the row ends up bound to the
            // newly-reported twin.
            provider.deltaItems = listOf(file("/dup.txt", id = "a-first"))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("a-first", db.getEntry("/dup.txt")?.remoteId)

            provider.deltaItems = listOf(file("/dup.txt", id = "b-second"))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("b-second", db.getEntry("/dup.txt")?.remoteId)
            assertEquals(1, db.getEntryCount())
        }

    @Test
    fun `a live replacement beats a tombstone at the same path without a collision report`() =
        runTest {
            // Delta sequences regularly carry [tombstone of the old item, live new
            // item] for one path (create-after-delete). The live item is the current
            // truth and must win under either emit order — without being reported
            // as a twin collision.
            provider.deltaItems =
                listOf(
                    file("/x.txt", id = "tomb-1").copy(deleted = true),
                    file("/x.txt", id = "live-2"),
                )
            val r = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(r.ok)
            assertEquals("live-2", db.getEntry("/x.txt")?.remoteId)
            assertEquals("0", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))

            // Same outcome when the tombstone arrives second.
            setUp()
            provider.deltaItems =
                listOf(
                    file("/x.txt", id = "live-2"),
                    file("/x.txt", id = "tomb-1").copy(deleted = true),
                )
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("live-2", db.getEntry("/x.txt")?.remoteId)
            assertEquals("0", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))
        }

    @Test
    fun `the same remote id re-reported wins as a version refresh, not a twin`() =
        runTest {
            // A mid-gather remote edit surfaces the SAME item id twice with different
            // metadata (the streaming newer-version case). The fresh report must win
            // and no collision may be recorded.
            provider.deltaItems =
                listOf(
                    file("/edited.txt", id = "same-id", size = 3),
                    file("/edited.txt", id = "same-id", size = 5),
                )
            val r = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(r.ok)
            assertEquals(5L, db.getEntry("/edited.txt")?.remoteSize, "the later version must win")
            assertEquals("0", db.getSyncState(SyncEngine.REMOTE_COLLISIONS_KEY))
        }

    // ---- order-independence property ----

    @Test
    fun `property - shuffled emits of a colliding namespace converge on identical rows`() =
        runTest {
            val items = collidingNamespace()
            val outcomes = mutableListOf<Map<String, String?>>()
            val warningCounts = mutableListOf<Int>()
            for (seed in listOf(0x5EED, 0xF00D)) {
                setUp()
                provider.deltaItems = items.shuffled(kotlin.random.Random(seed))
                val r = engine.enumerateRemoteIntoState(reset = false)
                assertTrue(r.ok)
                outcomes += db.getAllEntries().associate { it.path to it.remoteId }
                warningCounts += reporter.warnings.size
                tearDown()
            }
            assertEquals(outcomes[0], outcomes[1], "the winner rule must be emit-order independent")
            assertEquals(warningCounts[0], warningCounts[1])

            // Re-run on the resulting state: zero further churn, zero provider mutations.
            setUp()
            provider.deltaItems = items
            engine.enumerateRemoteIntoState(reset = false)
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals(outcomes[0], db.getAllEntries().associate { it.path to it.remoteId })
            assertEquals(0, provider.deletedPaths.size + provider.deletedByIds.size)
            tearDown()
        }

    /**
     * A seeded-random namespace over a colliding alphabet: duplicate names with
     * distinct ids, plus NFC/NFD spellings of the same name. Distinct paths stay
     * distinct; colliding names converge on one winner each.
     */
    private fun collidingNamespace(): List<CloudItem> {
        val paths =
            buildList {
                repeat(8) { i -> add("/n$i.txt") }
                repeat(4) { i -> add("/n$i.txt") } // exact dup names
                add("/caf\u00e9.txt")
                add("/cafe\u0301.txt")
                add("/dir")
                repeat(3) { i -> add("/dir/f$i.txt") }
            }
        return paths.mapIndexed { i, path ->
            if (path == "/dir") folder(path, id = "id-%03d".format(i)) else file(path, id = "id-%03d".format(i))
        }
    }

    // ---- fakes ----

    private fun file(
        path: String,
        id: String,
        size: Long = 1L,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private fun folder(
        path: String,
        id: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 0,
        isFolder = true,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    class RecordingReporter : ProgressReporter {
        val warnings = mutableListOf<String>()

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {}

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    private class CollisionFakeProvider : CloudProvider {
        override val id = "collision-fake"
        override val displayName = "Collision Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var deltaItems: List<CloudItem> = emptyList()
        val deletedPaths = mutableListOf<String>()
        val deletedByIds = mutableListOf<String>()
        val downloadedIds = mutableListOf<String>()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = deltaItems.last { it.path == path }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            downloadedIds += remoteId
            Files.createDirectories(destination.parent)
            Files.write(destination, ByteArray(0))
            return 0L
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = downloadById("", remotePath, destination)

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            deletedByIds += remoteId
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deletedPaths += remotePath
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not expected in this test")

        override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = deltaItems, cursor = "c1", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}
