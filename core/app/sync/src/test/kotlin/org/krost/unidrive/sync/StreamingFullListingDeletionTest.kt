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
 * A tracked file that a complete full listing no longer contains is a remote deletion, and the sync pass must plan the
 * same local delete whether it reconciles page by page (streaming) or in one piece. Every test here runs the same
 * scenario with `streamingReconciliation` off and on and asserts the same outcome; the guards that protect local data
 * (edited file, row written during the walk, unreachable item) hold in both modes.
 */
class StreamingFullListingDeletionTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-stream-del-root")
        db = StateDatabase(Files.createTempDirectory("ud-stream-del-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(streaming: Boolean) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = streaming,
        )

    private fun forceFullListing(cursorAfter: String) {
        db.setSyncState("delta_cursor", "")
        provider.deltaItems = emptyList()
        provider.deltaComplete = true
        provider.deltaCursor = cursorAfter
    }

    private fun bothModes(block: suspend (SyncEngine) -> Unit) {
        for (streaming in listOf(false, true)) {
            // A fresh sync root, database and fake per mode: the scenario starts from nothing each time.
            tearDown()
            setUp()
            try {
                runTest { block(engine(streaming)) }
            } catch (e: AssertionError) {
                throw AssertionError("streamingReconciliation=$streaming: ${e.message}", e)
            }
        }
    }

    @Test
    fun `a file a complete full listing no longer contains is deleted locally in both modes`() =
        bothModes { eng ->
            provider.files["/other.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/other.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("other.txt")), "the first pass downloads it")
            assertNotNull(db.getEntry("/other.txt"))

            forceFullListing("cursor-2")
            eng.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("other.txt")), "the absent file is removed locally")
            assertNull(db.getEntry("/other.txt"), "and its row is gone")
            assertEquals("cursor-2", db.getSyncState("delta_cursor"))
            assertTrue(provider.deletedPaths.isEmpty(), "a remote deletion is never echoed back to the remote")
        }

    @Test
    fun `a folder and its files absent from a complete full listing are deleted locally in both modes`() =
        bothModes { eng ->
            provider.files["/dir/a.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/dir", isFolder = true), item("/dir/a.txt", size = 3))
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("dir/a.txt")))

            forceFullListing("cursor-2")
            eng.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("dir/a.txt")), "the file under the vanished folder is removed")
            assertNull(db.getEntry("/dir/a.txt"))
            assertNull(db.getEntry("/dir"))
        }

    @Test
    fun `a locally edited file absent from a complete full listing is not deleted in either mode`() =
        bothModes { eng ->
            provider.files["/edited.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/edited.txt", size = 3))
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("edited.txt")))

            Files.writeString(syncRoot.resolve("edited.txt"), "my unsynced local edit, longer than the original")
            forceFullListing("cursor-2")
            eng.syncOnce()

            assertEquals(
                "my unsynced local edit, longer than the original",
                Files.readString(syncRoot.resolve("edited.txt")),
                "an edit that was never uploaded is not destroyed by a remote deletion",
            )
        }

    @Test
    fun `a row written after the gather started is not reaped by that gather in either mode`() =
        bothModes { eng ->
            provider.files["/late.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/late.txt", size = 3))
            eng.syncOnce()
            assertNotNull(db.getEntry("/late.txt"))

            forceFullListing("cursor-2")
            provider.duringDelta = {
                db.upsertEntry(db.getEntry("/late.txt")!!.copy(lastSynced = Instant.now()))
            }
            eng.syncOnce()

            assertNotNull(db.getEntry("/late.txt"), "the listing pages predate that write, their silence proves nothing")
            assertTrue(Files.exists(syncRoot.resolve("late.txt")))
        }

    @Test
    fun `an incomplete full listing deletes nothing in either mode`() =
        bothModes { eng ->
            provider.files["/kept.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/kept.txt", size = 3))
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("kept.txt")))

            forceFullListing("cursor-2")
            provider.deltaComplete = false
            eng.syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("kept.txt")), "a partial listing cannot show a deletion")
            assertNotNull(db.getEntry("/kept.txt"))
        }

    @Test
    fun `a tombstone a listing page carries still deletes locally in both modes`() =
        bothModes { eng ->
            provider.files["/tomb.txt"] = ByteArray(3)
            provider.deltaItems = listOf(item("/tomb.txt", size = 3))
            provider.deltaCursor = "cursor-1"
            eng.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("tomb.txt")))

            provider.deltaItems = listOf(item("/tomb.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("tomb.txt")))
            assertNull(db.getEntry("/tomb.txt"))
        }

    private fun item(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = null,
        deleted = deleted,
    )
}
