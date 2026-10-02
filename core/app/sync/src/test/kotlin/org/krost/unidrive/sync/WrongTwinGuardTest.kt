package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #402: where a folder holds two remote items with the same name, path-based
 * resolution picks one arbitrarily and a delete/move can hit the wrong twin.
 * The engine must aim delete/move at the row's real cloud id (SPI deleteById /
 * moveById) and must NEVER fall back to a path-addressed mutation on a path the
 * gather flagged as collided.
 */
class WrongTwinGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var skippedOpsPath: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: TwinFakeProvider
    private lateinit var reporter: TwinReporter
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-twin-root")
        dbDir = Files.createTempDirectory("ud-twin-db")
        cacheRoot = Files.createTempDirectory("ud-twin-cache")
        skippedOpsPath = Files.createTempDirectory("ud-twin-log").resolve("skipped-ops.jsonl")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = TwinFakeProvider()
        reporter = TwinReporter()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = reporter,
                cacheRoot = cacheRoot,
                cacheKey = "twin-test",
                skippedOpsLogPath = skippedOpsPath,
            )
        // Two same-named remote files (distinct ids/sizes) plus two bystanders, so a
        // single delete stays under the bulk-delete safeguards.
        provider.deltaItems =
            listOf(
                file("/dup.txt", id = "b-dup", size = 9),
                file("/dup.txt", id = "a-dup", size = 7),
                file("/keep1.txt", id = "keep-1", size = 1),
                file("/keep2.txt", id = "keep-2", size = 2),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first sync binds the row to one deterministic twin`() =
        runTest {
            engine.syncOnce()
            val row = db.getEntry("/dup.txt")
            assertNotNull(row)
            assertEquals("a-dup", row.remoteId, "smallest id wins on a never-tracked pair")
            assertTrue("a-dup" in provider.downloadedIds, "the winner is what got downloaded")
            assertFalse(
                "b-dup" in provider.downloadedIds,
                "the suppressed twin must not be downloaded",
            )
        }

    @Test
    fun `deleting the local copy of a collided path deletes by id and never by path`() =
        runTest {
            engine.syncOnce()
            Files.delete(syncRoot.resolve("dup.txt"))

            engine.syncOnce()

            assertEquals(
                listOf("a-dup"),
                provider.deletedByIds,
                "the delete must aim at the tracked twin's id",
            )
            assertTrue(
                provider.deletedPaths.isEmpty(),
                "a collided path must NEVER reach provider.delete(path) — wrong-twin hazard",
            )
            // Only the tracked twin is gone; the bystanders and the remote state are untouched.
            assertEquals(setOf("keep-1", "keep-2"), db.getAllEntries().map { it.remoteId }.toSet() - "a-dup")
            val trashed = db.recovery.trashedEntries()
            assertTrue(trashed.any { it.remoteId == "a-dup" })
        }

    @Test
    fun `renaming the local copy of a collided path moves by id and never by path`() =
        runTest {
            engine.syncOnce()
            Files.move(syncRoot.resolve("dup.txt"), syncRoot.resolve("renamed.txt"))

            engine.syncOnce()

            assertEquals(
                listOf(Triple("a-dup", "/dup.txt", "/renamed.txt")),
                provider.movedByIds,
                "the move must aim at the tracked twin's id",
            )
            assertTrue(
                provider.movedPaths.isEmpty(),
                "a collided path must NEVER reach provider.move(from, to) — wrong-twin hazard",
            )
            assertEquals("a-dup", db.getEntry("/renamed.txt")?.remoteId)
        }

    @Test
    fun `a path-addressed action on a collided path is refused and audited`() =
        runTest {
            engine.syncOnce() // gather flags /dup.txt as collided in this engine instance

            assertTrue(engine.refuseCollidedPath(SyncAction.DeleteRemote("/dup.txt")))
            assertFalse(engine.refuseCollidedPath(SyncAction.DeleteRemote("/keep1.txt")))

            val lines = Files.readString(skippedOpsPath)
            assertTrue("collided_path_no_remote_id" in lines)
            assertTrue("/dup.txt" in lines)
            assertFalse("/keep1.txt" in lines)
        }

    // ---- fakes ----

    private fun file(
        path: String,
        id: String,
        size: Long,
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

    class TwinReporter : ProgressReporter {
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

    private class TwinFakeProvider : CloudProvider {
        override val id = "twin-fake"
        override val displayName = "Twin Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var deltaItems: List<CloudItem> = emptyList()
        val deletedPaths = mutableListOf<String>()
        val deletedByIds = mutableListOf<String>()
        val movedPaths = mutableListOf<Pair<String, String>>()
        val movedByIds = mutableListOf<Triple<String, String, String>>()
        val downloadedIds = mutableListOf<String>()

        private fun itemOf(id: String): CloudItem = deltaItems.first { it.id == id }

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = deltaItems.last { it.path == path }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            downloadedIds += remoteId
            val bytes = ByteArray(itemOf(remoteId).size.toInt())
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return bytes.size.toLong()
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("path download must not happen for id-carrying rows")

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
        ): CloudItem {
            movedPaths += fromPath to toPath
            return itemOf("a-dup")
        }

        override suspend fun moveById(
            remoteId: String,
            fromPath: String,
            toPath: String,
        ): CloudItem {
            movedByIds += Triple(remoteId, fromPath, toPath)
            return itemOf(remoteId)
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = deltaItems, cursor = "c1", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}
