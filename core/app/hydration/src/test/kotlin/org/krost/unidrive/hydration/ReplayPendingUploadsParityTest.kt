package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.LocalScanner
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #560 U1: which pending rows the start-up replay picks. `StateDatabase.pendingUploadPaths()` returns every alive,
 * hydrated, never-uploaded file row — rows LocalScanner wrote for files in the sync root as well as rows the mount
 * wrote. `HydrationImpl.replayPendingUploads` then keeps only rows with a hydration cache copy, so a sync-root file
 * the scanner found is left to the rescan / a sync and is never uploaded from a cache it does not have.
 */
class ReplayPendingUploadsParityTest {
    @Test
    fun `a permanent refusal releases the slot and survives a database reopen until content changes`() =
        runTest {
            val syncRoot = Files.createTempDirectory("refusal-root")
            val cacheRoot = Files.createTempDirectory("refusal-cache")
            val dbPath = Files.createTempDirectory("refusal-db").resolve("state.db")
            val provider = MinimalFakeProvider()
            provider.uploadRefusedRemaining.set(1)
            var db = StateDatabase(dbPath)
            db.initialize()
            try {
                fun newEngine() = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
                var engine = newEngine()
                var hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = this, failedReplayDelayMs = 0)
                hydration.create("conn", "create", "/refused.txt")
                val cache = engine.resolveCachePath("/refused.txt")
                Files.createDirectories(cache.parent)
                Files.writeString(cache, "regular non-empty content")
                hydration.openForWrite("conn", "first", "/refused.txt", cache)
                advanceUntilIdle()
                assertEquals(1, provider.uploadAttempts())
                assertFalse(hydration.hasUploadSlot("/refused.txt"), "terminal failure releases the queued path")
                assertTrue(db.uploadRefusal("/refused.txt") != null)
                assertTrue(db.getEntry("/refused.txt")?.lastErrorAt != null)

                db.close()
                db = StateDatabase(dbPath)
                db.initialize()
                engine = newEngine()
                hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = this, failedReplayDelayMs = 0)
                assertEquals(0, hydration.replayPendingUploads())
                hydration.openForWrite("conn", "unchanged", "/refused.txt", cache)
                advanceUntilIdle()
                assertEquals(1, provider.uploadAttempts(), "a fresh daemon never replays the refused bytes")
                assertEquals("regular non-empty content", Files.readString(cache), "the only copy remains intact")

                Files.writeString(cache, "changed content is eligible again")
                assertEquals(1, hydration.replayPendingUploads())
                advanceUntilIdle()
                assertEquals(2, provider.uploadAttempts())
                assertEquals("changed content is eligible again", provider.uploadedContent("/refused.txt"))
            } finally {
                db.close()
            }
        }

    @Test
    fun `replay uploads the mount-written row with a cache copy and skips the LocalScanner row without one`() =
        runTest {
            val syncRoot = Files.createTempDirectory("ud-560-replay-root")
            val cacheRoot = Files.createTempDirectory("ud-560-replay-cache")
            val db = StateDatabase(Files.createTempDirectory("ud-560-replay-db").resolve("state.db"))
            db.initialize()
            try {
                val provider = MinimalFakeProvider()
                val engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
                val hydration = HydrationImpl(syncEngine = engine, stateDb = db, recoveryUploadScope = this)

                // A file dropped into the sync root, recorded by the scanner (no cache copy).
                Files.writeString(syncRoot.resolve("scanned.txt"), "sync-root bytes")
                LocalScanner(syncRoot, db).scan()
                // A file created through the mount whose upload never landed (its bytes only in the cache).
                db.upsertEntry(
                    SyncEntry(
                        path = "/mounted.txt",
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = Instant.now().toEpochMilli(),
                        localSize = 0L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
                val cache = engine.resolveCachePath("/mounted.txt")
                Files.createDirectories(cache.parent)
                Files.writeString(cache, "cache bytes")
                assertEquals(listOf("/mounted.txt", "/scanned.txt"), db.pendingUploadPaths(), "precondition: both qualify in SQL")
                val scannedRow = db.getEntry("/scanned.txt")

                val enqueued = hydration.replayPendingUploads()
                advanceUntilIdle()

                assertEquals(1, enqueued, "only the row with a cache copy is replayed")
                assertEquals("cache bytes", provider.uploadedContent("/mounted.txt"))
                assertNull(provider.uploadedContent("/scanned.txt"), "the scanner row is not uploaded by the replay")
                assertEquals(scannedRow, db.getEntry("/scanned.txt"), "and its row is left as it was")
            } finally {
                db.close()
            }
        }
}
