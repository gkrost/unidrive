package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The watermark rule (#337, #148): an upload row records the file's stats as
 * they were BEFORE the transfer started. A write that lands while the upload
 * is in flight must never be absorbed into the recorded baseline — the next
 * scan must see the difference and send the new content again. An unchanged
 * file keeps today's behaviour (nothing re-planned).
 */
class SyncEngineWatermarkStatTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-337-root")
        db = StateDatabase(Files.createTempDirectory("ud-337-db").resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = Files.createTempDirectory("ud-337-cache"),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // Edits the file being uploaded while the (fake) transfer is in flight and
    // moves its mtime clearly past the pre-upload stat, so mtime granularity
    // cannot hide the difference.
    private fun editDuringUpload(newContent: String, newMtime: Long) {
        provider.duringUpload = { path ->
            Files.writeString(path, newContent)
            Files.setLastModifiedTime(path, FileTime.fromMillis(newMtime))
            provider.duringUpload = null
        }
    }

    private fun seedSyncedRow(path: String, content: String, mtimeMillis: Long) {
        val file = syncRoot.resolve(path.trimStart('/'))
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMillis))
        val now = Instant.parse("2026-03-28T12:00:00Z")
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = "hash-$path",
                remoteSize = content.length.toLong(),
                remoteModified = now,
                localMtime = mtimeMillis,
                localSize = content.length.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = now,
            ),
        )
        db.setSyncState("delta_cursor", "seeded-cursor")
        provider.deltaItems = emptyList()
    }

    @Test
    fun `an edit landing during an upload is planned again by the next scan`() =
        runTest {
            seedSyncedRow("/doc.txt", "seed content", 900_000_000L)
            // The user's edit that the first upload sends.
            val file = syncRoot.resolve("doc.txt")
            Files.writeString(file, "first version")
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000L))
            editDuringUpload("edited version", 1_000_060_000L)

            engine.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/doc.txt"), "precondition: the upload ran")
            val row = assertNotNull(db.getEntry("/doc.txt"))
            assertEquals(
                1_000_000_000L,
                row.localMtime,
                "the row must record the PRE-upload mtime, not the mid-upload edit's",
            )

            provider.uploadedPaths.clear()
            provider.deltaCursor = "cursor-2"
            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertEquals(
                listOf("/doc.txt"),
                provider.uploadedPaths,
                "the next scan must see the recorded watermark lag the file and re-upload the edit",
            )
        }

    @Test
    fun `an upload whose file did not change during the transfer is not planned again`() =
        runTest {
            seedSyncedRow("/stable.txt", "seed content", 900_000_000L)
            val file = syncRoot.resolve("stable.txt")
            Files.writeString(file, "stable content")
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000L))

            engine.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/stable.txt"), "precondition: the upload ran")
            val row = assertNotNull(db.getEntry("/stable.txt"))
            assertEquals(1_000_000_000L, row.localMtime)

            provider.uploadedPaths.clear()
            provider.deltaCursor = "cursor-2"
            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertEquals(emptyList(), provider.uploadedPaths, "an unchanged file must not be re-planned")
        }

    @Test
    fun `a keep-both upload of a locally-modified remotely-deleted file records the pre-upload mtime`() =
        runTest {
            // The applyKeepBoth upload branch runs for a Conflict(local=MODIFIED,
            // remote=DELETED) under KEEP_BOTH: the local file wins and is re-uploaded.
            seedSyncedRow("/keep.txt", "kept version", 1_000_000_000L)
            // The user edits the file while the remote deletes it.
            val file = syncRoot.resolve("keep.txt")
            Files.writeString(file, "kept version, edited")
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_060_000L))
            val editedMtime = 1_000_060_000L
            val deletedItem =
                org.krost.unidrive.CloudItem(
                    id = "id-/keep.txt",
                    name = "keep.txt",
                    path = "/keep.txt",
                    size = 0,
                    isFolder = false,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
            provider.deltaItems = listOf(deletedItem)
            provider.deltaCursor = "cursor-tombstone"
            editDuringUpload("edited during the conflict upload", editedMtime + 60_000L)

            engine.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/keep.txt"), "precondition: the keep-both upload ran")
            val row = assertNotNull(db.getEntry("/keep.txt"))
            assertEquals(
                editedMtime,
                row.localMtime,
                "the conflict upload must record the PRE-upload mtime, not the mid-upload edit's",
            )

            provider.uploadedPaths.clear()
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()
            assertTrue(
                provider.uploadedPaths.contains("/keep.txt"),
                "the next scan must plan the mid-upload edit again",
            )
        }
}
