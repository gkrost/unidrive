package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A provider whose delta() walks the whole tree on every call (localfs) hands the engine a cursor
 * after the first pass and never sends tombstones. The engine used to treat every later pass as
 * incremental, so the absence sweep never ran and a file deleted on the remote stayed local for
 * good (and came back on the remote after any reset). Such a provider declares
 * [org.krost.unidrive.CloudProvider.deltaIsFullListing] and every pass then counts as a full one.
 */
class FullListingDeltaTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val modified = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-422-root")
        val dbPath = Files.createTempDirectory("ud-422-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider()
        provider.deltaFullListing = true
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun item(path: String) =
        CloudItem(
            id = path,
            name = path.substringAfterLast("/"),
            path = path,
            size = 4,
            isFolder = false,
            modified = modified,
            created = modified,
            hash = null,
            mimeType = null,
        )

    private fun remoteHolds(vararg paths: String) {
        provider.deltaItems = paths.map { item(it) }
        paths.forEach { provider.files[it] = "data".toByteArray() }
    }

    @Test
    fun `a file deleted on a full-listing remote is removed locally on a later sync`() =
        runTest {
            val eng = engine()
            remoteHolds("/keep.txt", "/gone.txt")
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("gone.txt")), "precondition: mirrored down")

            // The remote loses gone.txt; the provider just stops listing it, as localfs does.
            remoteHolds("/keep.txt")
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("gone.txt")), "the remote deletion must arrive")
            assertNull(db.getEntry("/gone.txt"))
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")), "a file still listed is left alone")
            assertNotNull(db.getEntry("/keep.txt"))
        }

    @Test
    fun `the deletion is not undone by the next sync`() =
        runTest {
            val eng = engine()
            remoteHolds("/keep.txt", "/gone.txt")
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            remoteHolds("/keep.txt")
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()
            provider.deltaCursor = "cursor-3"
            eng.syncOnce()

            assertEquals(emptyList(), provider.uploadedPaths, "the deleted file must not be re-uploaded")
            assertFalse(Files.exists(syncRoot.resolve("gone.txt")))
        }

    @Test
    fun `a provider that sends incremental changes is still not swept on a warm cursor`() =
        runTest {
            provider.deltaFullListing = false
            val eng = engine()
            remoteHolds("/keep.txt", "/other.txt")
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()

            // Incremental page that does not mention other.txt: nothing changed there.
            remoteHolds("/keep.txt")
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("other.txt")), "absence from an incremental page is not a deletion")
        }
}
