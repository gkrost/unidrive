package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.time.Duration
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
 * Clearing a finished scan: the staged rows are the whole listing, and a recording of a large drive caught the
 * delete holding the database monitor for 23 s while every reader waited. The monitor is free between the steps,
 * and the checkpoint never outlives the rows it stands for.
 */
class StateDatabaseScanClearTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-scan-clear").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun staged(
        prefix: String,
        from: Int,
        count: Int,
    ) = (from until from + count).map { i ->
        CloudItem(
            id = "$prefix-$i",
            name = "f$i",
            path = "/f$i",
            size = 1,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = null,
            hash = null,
            mimeType = null,
            parentId = null,
        )
    }

    private fun stage(
        scanId: String,
        prefix: String,
        count: Int,
    ) = db.persistScanPage(scanId, staged(prefix, 0, count), marker = "m-$prefix")

    @Test
    fun `a scan that is alone in the table is cleared in one transaction, with its checkpoint`() {
        val scanId = db.beginScan(initialMarker = null)
        stage(scanId, "a", StateDatabase.SCAN_CLEAR_CHUNK_ROWS + 10)
        var commits = 0
        db.batchCommitHook = { commits++ }
        var gaps = 0
        db.scanClearChunkGap = { gaps++ }

        db.completeScan(scanId)

        assertEquals(0, db.loadStagedItems(scanId).size)
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
        assertEquals(1, commits, "rows and checkpoint go in one transaction, as before")
        assertEquals(0, gaps)
    }

    @Test
    fun `with rows of another scan present, the rows of this one go in chunks and the monitor is free between them`() {
        val chunk = StateDatabase.SCAN_CLEAR_CHUNK_ROWS
        val scanId = db.beginScan(initialMarker = null)
        stage(scanId, "a", 2 * chunk + 1)
        val other = db.beginScan(initialMarker = null) // the table now holds two scans' rows
        stage(other, "b", 7)
        val heldInGap = mutableListOf<Boolean>()
        val checkpointInGap = mutableListOf<String?>()
        val rowsLeftInGap = mutableListOf<Int>()
        db.scanClearChunkGap = {
            heldInGap += Thread.holdsLock(db)
            checkpointInGap += db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)
            rowsLeftInGap += db.loadStagedItems(scanId).size
        }

        db.completeScan(scanId)

        assertEquals(listOf(false, false), heldInGap, "two chunk boundaries, and neither holds the monitor")
        assertEquals(listOf<String?>(null, null), checkpointInGap, "the checkpoint is gone before the first row is, so a crash resumes nothing")
        assertEquals(listOf(chunk + 1, 1), rowsLeftInGap, "a chunk of rows per transaction")
        assertEquals(0, db.loadStagedItems(scanId).size)
        assertEquals(7, db.loadStagedItems(other).size, "the rows of the other scan stay")
    }

    @Test
    fun `a stale checkpoint is cleared without the monitor being held, and the verdict is unchanged`() {
        val chunk = StateDatabase.SCAN_CLEAR_CHUNK_ROWS
        val older = db.beginScan(initialMarker = null)
        stage(older, "b", 3)
        val scanId = db.beginScan(initialMarker = null) // the checkpoint names this one
        stage(scanId, "a", chunk + 1)
        db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT, Instant.now().minus(Duration.ofDays(1)).toString())
        val held = mutableListOf<Boolean>()
        db.scanClearChunkGap = { held += Thread.holdsLock(db) }

        val active = db.getActiveScan(staleThreshold = Duration.ofHours(6))

        assertNull(active, "a stale checkpoint is no active scan")
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        assertEquals(0, db.loadStagedItems(scanId).size, "its rows are discarded")
        assertEquals(3, db.loadStagedItems(older).size)
        assertEquals(listOf(false), held, "one chunk boundary, outside the monitor the verdict was read under")
    }

    @Test
    fun `a fresh checkpoint is still returned whole`() {
        val scanId = db.beginScan(initialMarker = "resume")
        stage(scanId, "a", 3)

        val active = db.getActiveScan(staleThreshold = Duration.ofHours(6))

        assertNotNull(active)
        assertEquals(scanId, active.scanId)
        assertEquals("m-a", active.marker)
        assertFalse(db.loadStagedItems(scanId).isEmpty(), "reading the checkpoint clears nothing")
    }
}
