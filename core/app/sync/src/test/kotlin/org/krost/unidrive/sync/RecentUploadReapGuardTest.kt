package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full-sync absence-implies-deletion sweep (detectMissingAfterFullSync)
 * diffs the DB against the live remote inventory and marks any DB row whose remoteId
 * is absent as deleted. Cloud delta is eventually consistent: a path the engine itself
 * JUST uploaded is not guaranteed to appear in the very next full enumeration. Without
 * a guard the sweep concludes "not in delta → deleted", queues a deletion, the next
 * cycle re-detects + re-uploads — a steady-state churn.
 *
 * The guard keys off recently-uploaded paths (stamped at upload time), so it protects
 * only what the engine pushed; a freshly-downloaded path whose remote is genuinely
 * deleted is still reaped. These two named tests pin both orthogonal invariants:
 *   - a just-uploaded path absent from a lagging delta is NOT reaped (no churn);
 *   - a just-downloaded path whose remote is genuinely gone IS reaped (no data lag).
 */
class RecentUploadReapGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-reap-root")
        val dbPath = Files.createTempDirectory("ud-reap-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
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

    @Test
    fun `recently_uploaded_path_absent_from_an_incomplete_delta_is_not_reaped`() =
        runTest {
            val eng = engine()

            // Baseline sync (nothing remote).
            provider.deltaItems = emptyList()
            eng.syncOnce()

            // User drops a new local file; the engine uploads it (applyUpload runs,
            // stamping the recent-upload watermark).
            Files.writeString(syncRoot.resolve("geheim.txt"), "secret")
            eng.syncOnce()
            assertTrue(provider.uploadedPaths.contains("/geheim.txt"), "engine must upload the new local file")
            assertNotNull(db.getEntry("/geheim.txt"))

            // Next full-sync pass: cursor reset forces a full enumeration, but the
            // eventually-consistent delta does NOT yet list the just-uploaded item.
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            eng.syncOnce()

            // The path must survive — no spurious deletion, no re-upload churn.
            assertNotNull(db.getEntry("/geheim.txt"), "a just-uploaded path must NOT be reaped by a lagging delta")
            assertTrue(Files.exists(syncRoot.resolve("geheim.txt")), "the local file must NOT be deleted")
        }

    @Test
    fun `genuinely_deleted_downloaded_path_is_still_reaped`() =
        runTest {
            val eng = engine()

            // Remote has a file; the engine downloads it (NOT an upload — no
            // recent-upload watermark is stamped for downloads).
            provider.files["/tracked.txt"] = ByteArray(50)
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertNotNull(db.getEntry("/tracked.txt"))
            assertTrue(Files.exists(syncRoot.resolve("tracked.txt")))

            // The remote genuinely deletes it; a full enumeration no longer lists it.
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            eng.syncOnce()

            // No upload watermark protects a downloaded path → the genuine delete reaps.
            assertNull(db.getEntry("/tracked.txt"), "a genuinely-deleted downloaded path must still be reaped")
            assertFalse(Files.exists(syncRoot.resolve("tracked.txt")), "the local copy of a deleted remote file is removed")
        }

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = org.krost.unidrive.CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = java.time.Instant.now(),
        created = java.time.Instant.now(),
        hash = "hash-$path",
        mimeType = null,
        deleted = deleted,
    )
}
