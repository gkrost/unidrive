package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
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

/**
 * The watermark rule (#337, #148) for the mount's write-back (moved here from
 * `SyncEngineWatermarkStatTest` with the operation, #560 U3): an upload from the cache records the
 * cache copy's stats as they were BEFORE the transfer started, so a write that lands while the
 * upload is in flight is never absorbed into the recorded baseline.
 */
class MountEngineWatermarkStatTest {
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

    @Test
    fun `a write-back whose cache copy changes during the upload records the pre-upload watermark`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()
            // #319: the write-back requires the row the FUSE create flow wrote.
            seedLocalOnlyRow("/local.txt")
            val cacheCopy = Files.createTempDirectory("ud-337-wb").resolve("local.txt")
            Files.writeString(cacheCopy, "first version")
            val preUploadMtime = Files.getLastModifiedTime(cacheCopy).toMillis()

            editDuringUpload("edited version", preUploadMtime + 60_000L)
            engine.uploadFromCache("/local.txt", cacheCopy)

            val row = assertNotNull(db.getEntry("/local.txt"))
            assertEquals(
                preUploadMtime,
                row.localMtime,
                "the write-back must record the cache copy's PRE-upload mtime",
            )
        }

    private fun seedLocalOnlyRow(path: String) {
        val now = Instant.parse("2026-03-28T12:00:00Z")
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = now.toEpochMilli(),
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = now,
            ),
        )
    }
}
