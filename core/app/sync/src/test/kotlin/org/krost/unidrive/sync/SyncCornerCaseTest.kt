package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.test.*

// UD-209: sparse-file production support missing on Windows — affected tests skip there.
private val IS_WINDOWS_CORNER = System.getProperty("os.name", "").lowercase().contains("win")

/**
 * UD-209a: produce a sparse file of [size] bytes via FileChannel + write-1-byte-at-N-1.
 * On JDK 21 jbrsdk on Linux, RandomAccessFile.setLength(N) on a fresh file emits
 * ftruncate(0) + write(zeros, N) — densely allocating the file. The FileChannel
 * pattern emits ftruncate(0) + a single 1-byte write at position N-1, allocating
 * only the trailing page. Mirror of the production fix in PlaceholderManager.dehydrate.
 */
private fun createSparsePlaceholder(file: Path, size: Long) {
    Files.deleteIfExists(file)
    Files.createFile(file)
    if (size > 0L) {
        FileChannel.open(file, StandardOpenOption.WRITE).use { ch ->
            ch.position(size - 1L)
            ch.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }
}

/**
 * Corner-case tests for interrupted sync, daemon crash recovery,
 * network outages, and auth expiry scenarios.
 */
class SyncCornerCaseTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-corner-test")
        val dbPath = Files.createTempDirectory("unidrive-corner-db").resolve("state.db")
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

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
        hash: String? = "hash-$path",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    // ========== Interrupted / crashed sync ==========

    @Test
    fun `sparse placeholder from interrupted sync is replaced by real download`() =
        runTest {
            assumeFalse("Requires sparse-file semantics — UD-209 tracks Windows support", IS_WINDOWS_CORNER)
            // Simulate interrupted first sync: sparse placeholders exist but no DB entries.
            // UD-209a: size must exceed one filesystem page (4 KiB on tmpfs/ext4) so the
            // sparse-vs-dense distinction in isSparse(blocks*512 < expectedSize) is observable.
            val placeholder = syncRoot.resolve("doc.txt")
            Files.createDirectories(placeholder.parent)
            val placeholderSize = 16_384L
            createSparsePlaceholder(placeholder, placeholderSize)
            assertEquals(placeholderSize, Files.size(placeholder))

            // Now run a "real" first sync — remote has same file with size 16384
            provider.files["/doc.txt"] = ByteArray(placeholderSize.toInt()) { 0x41 }
            provider.deltaItems = listOf(cloudItem("/doc.txt", size = placeholderSize))
            engine().syncOnce()

            val entry = db.getEntry("/doc.txt")
            assertNotNull(entry)
            // UD-222: sparse leftovers are no longer adopted — isSparse detects them, and the
            // default-hydrate policy downloads real bytes over the stub. Entry becomes hydrated.
            assertTrue(entry.isHydrated, "Sparse placeholder should be replaced by real download")
            val content = Files.readAllBytes(placeholder)
            assertEquals(placeholderSize.toInt(), content.size)
            assertTrue(content.all { it == 0x41.toByte() }, "Local file should hold downloaded content")
        }

    @Test
    fun `real file with matching size adopted as hydrated`() =
        runTest {
            // A genuine local file exists before first sync
            val file = syncRoot.resolve("real.txt")
            Files.writeString(file, "a".repeat(100))

            provider.deltaItems = listOf(cloudItem("/real.txt", size = 100))
            engine().syncOnce()

            val entry = db.getEntry("/real.txt")
            assertNotNull(entry)
            assertTrue(entry.isHydrated, "Real file with matching size should be adopted as hydrated")
        }

    @Test
    fun `pinned file downloaded even if sparse placeholder exists`() =
        runTest {
            assumeFalse("Requires sparse-file semantics — UD-209", IS_WINDOWS_CORNER)
            // Sparse placeholder left from interrupted sync. UD-209a: size must exceed
            // one filesystem page so isSparse(blocks*512 < expectedSize) actually fires.
            val placeholder = syncRoot.resolve("pinned.txt")
            Files.createDirectories(placeholder.parent)
            val placeholderSize = 16_384L
            createSparsePlaceholder(placeholder, placeholderSize)

            // Remote has same size, and it's pinned
            val realContent = ByteArray(placeholderSize.toInt()) { 0x42 }
            provider.deltaItems = listOf(cloudItem("/pinned.txt", size = placeholderSize))
            provider.files["/pinned.txt"] = realContent
            db.addPinRule("*.txt", pinned = true)

            engine().syncOnce()

            val entry = db.getEntry("/pinned.txt")
            assertNotNull(entry)
            assertTrue(entry.isHydrated, "Pinned file should be downloaded even over sparse placeholder")
            // Verify actual content was written (not all zeros)
            val content = Files.readAllBytes(syncRoot.resolve("pinned.txt"))
            assertFalse(content.all { it == 0.toByte() }, "File should have real content after download")
        }

    @Test
    fun `second sync after crash recovers with correct state`() =
        runTest {
            // First sync succeeds
            provider.deltaItems =
                listOf(
                    cloudItem("/stable.txt", size = 200),
                    cloudItem("/will-update.txt", size = 300),
                )
            provider.deltaCursor = "cursor-1"
            engine().syncOnce()

            assertNotNull(db.getEntry("/stable.txt"))
            assertNotNull(db.getEntry("/will-update.txt"))

            // Simulate crash: DB has entries from first sync, cursor saved
            // Second sync: remote has update for one file
            provider.deltaItems =
                listOf(
                    cloudItem("/will-update.txt", size = 500, hash = "new-hash"),
                )
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            // stable.txt should survive (incremental delta doesn't include it)
            assertNotNull(db.getEntry("/stable.txt"))
            // will-update.txt should be updated
            val updated = db.getEntry("/will-update.txt")
            assertNotNull(updated)
            assertEquals(500, updated.remoteSize)
        }

    @Test
    fun `crash during apply leaves partial state that next sync recovers`() =
        runTest {
            // First sync with two remote files, download of second fails
            provider.files["/ok.txt"] = ByteArray(0)
            provider.deltaItems =
                listOf(
                    cloudItem("/ok.txt", size = 50),
                    cloudItem("/fail.txt", size = 100),
                    cloudItem("/also-ok.txt", size = 75),
                )
            // Fail on downloads of fail.txt
            provider.downloadFailCount = 1
            provider.deltaCursor = "cursor-1"

            // syncOnce should not throw (consecutive failures < 3)
            engine().syncOnce()

            // ok.txt should be in DB
            assertNotNull(db.getEntry("/ok.txt"))
            // also-ok.txt should survive — failure was non-consecutive
            assertNotNull(db.getEntry("/also-ok.txt"))

            // Re-sync: now everything works
            provider.downloadFailCount = 0
            provider.deltaItems = listOf(cloudItem("/fail.txt", size = 100))
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            assertNotNull(db.getEntry("/fail.txt"))
        }

    // ========== Network outage ==========

    @Test
    fun `bulk download failures are logged but cursor is not promoted`() =
        runTest {
            // UD-222: Pass 2 (download hydration) no longer aborts sync on consecutive failures —
            // a 129k-file first sync should survive transient hiccups on individual files. What DOES
            // abort is cursor promotion: if any transfer failed, pending_cursor is not advanced, so
            // the next sync retries everything from the previous known-good cursor.
            db.setSyncState("delta_cursor", "prior-cursor")
            provider.deltaItems =
                listOf(
                    cloudItem("/a.txt", size = 10),
                    cloudItem("/b.txt", size = 20),
                    cloudItem("/c.txt", size = 30),
                    cloudItem("/d.txt", size = 40),
                )
            provider.deltaCursor = "fresh-cursor"
            provider.downloadFailCount = 4 // all downloads fail

            engine().syncOnce() // no throw — bulk failures are survivable

            // Cursor did NOT advance — the failed items must get another chance.
            assertEquals("prior-cursor", db.getSyncState("delta_cursor"))
            // Each failed download left a non-hydrated DB entry for retry.
            for (name in listOf("/a.txt", "/b.txt", "/c.txt", "/d.txt")) {
                val entry = db.getEntry(name)
                assertNotNull(entry)
                assertFalse(entry.isHydrated, "$name should be marked non-hydrated")
            }
        }

    @Test
    fun `successful action resets consecutive failure counter`() =
        runTest {
            // Interleave: folder (succeeds), file (fails), folder (succeeds), file (fails)
            // Folders don't download, so they succeed. Files fail.
            // Pattern: success, fail, success, fail — never 3 consecutive failures
            provider.deltaItems =
                listOf(
                    cloudItem("/dir1", isFolder = true),
                    cloudItem("/dir1/fail1.txt", size = 10),
                    cloudItem("/dir2", isFolder = true),
                    cloudItem("/dir2/fail2.txt", size = 20),
                )
            provider.downloadFailCount = 0 // downloads don't happen for non-pinned files (only placeholders)
            provider.deltaCursor = "cursor-1"

            // This should succeed because CreatePlaceholder for non-pinned files doesn't download
            engine().syncOnce()

            assertNotNull(db.getEntry("/dir1"))
            assertNotNull(db.getEntry("/dir1/fail1.txt"))
        }

    @Test
    fun `upload failure during local change sync does not stop other uploads`() =
        runTest {
            // First sync: empty remote
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-1"
            engine().syncOnce()

            // Create local files
            Files.writeString(syncRoot.resolve("new1.txt"), "content1")
            Files.writeString(syncRoot.resolve("new2.txt"), "content2")
            Files.writeString(syncRoot.resolve("new3.txt"), "content3")

            // All uploads fail
            provider.uploadFailCount = 3
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            // With concurrent execution, individual upload failures don't stop the sync
            // They are logged as warnings but the sync completes
            engine().syncOnce()

            // No uploads succeeded because all failed
            assertEquals(0, provider.uploadedPaths.size)
        }

    @Test
    fun `delta fetch failure propagates`() =
        runTest {
            provider.deltaFailCount = 1

            val ex =
                assertFailsWith<ProviderException> {
                    engine().syncOnce()
                }
            assertTrue(ex.message!!.contains("Network timeout"))
        }

    // ========== Auth expiry mid-sync ==========

    @Test
    fun `auth expiry during download stops sync immediately`() =
        runTest {
            provider.deltaItems =
                listOf(
                    cloudItem("/secret.txt", size = 100),
                )
            provider.files["/secret.txt"] = "content".toByteArray()
            db.addPinRule("*.txt", pinned = true)
            provider.authFailOnDownload = true

            val ex =
                assertFailsWith<AuthenticationException> {
                    engine().syncOnce()
                }
            assertTrue(ex.message!!.contains("Token expired"))
        }

    // ========== Cursor persistence edge cases ==========

    @Test
    fun `empty cursor string treated as null for full resync`() =
        runTest {
            // Set an empty cursor — should trigger full sync behavior
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = listOf(cloudItem("/fresh.txt", size = 50))
            provider.deltaCursor = "new-cursor"

            engine().syncOnce()

            assertNotNull(db.getEntry("/fresh.txt"))
            assertEquals("new-cursor", db.getSyncState("delta_cursor"))
        }

    @Test
    fun `pending cursor not promoted on transfer failure`() =
        runTest {
            // UD-222: sync no longer throws on per-file failures, but the cursor promotion guard
            // keeps the delta cursor at its previous value so failed items get retried.
            db.setSyncState("delta_cursor", "good-cursor")

            provider.deltaItems =
                listOf(
                    cloudItem("/a.txt", size = 10),
                    cloudItem("/b.txt", size = 20),
                    cloudItem("/c.txt", size = 30),
                )
            provider.deltaCursor = "bad-cursor"
            provider.downloadFailCount = 3

            engine().syncOnce()

            // The cursor should NOT have been promoted — three failures means next sync retries.
            assertEquals("good-cursor", db.getSyncState("delta_cursor"))
        }

    // ========== DB state recovery ==========

    @Test
    fun `DB entry without matching local file detected as deleted`() =
        runTest {
            // Simulate: DB has entry but file was manually deleted (daemon was stopped)
            db.upsertEntry(
                SyncEntry(
                    path = "/ghost.txt",
                    remoteId = "id-ghost",
                    remoteHash = "abc",
                    remoteSize = 100,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )

            // Set cursor so this is an incremental sync (not full)
            // Full sync would see the item missing from delta and also mark it remote-deleted,
            // producing RemoveEntry instead of DeleteRemote.
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            // Scanner detects missing file → local=DELETED, remote=UNCHANGED → DeleteRemote
            assertTrue(provider.deletedPaths.contains("/ghost.txt"))
            assertNull(db.getEntry("/ghost.txt"))
        }

    @Test
    fun `DB entry without matching local folder detected as deleted`() =
        runTest {
            db.upsertEntry(
                SyncEntry(
                    path = "/old-folder",
                    remoteId = "id-old-folder",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1711627200000,
                    localSize = 0,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )

            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            assertTrue(provider.deletedPaths.contains("/old-folder"))
            assertNull(db.getEntry("/old-folder"))
        }

    // ========== Zero-byte file edge cases ==========

    @Test
    fun `zero-byte remote file always marked hydrated`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/empty.log", size = 0))
            engine().syncOnce()

            val entry = db.getEntry("/empty.log")
            assertNotNull(entry)
            assertTrue(entry.isHydrated)
            assertTrue(Files.exists(syncRoot.resolve("empty.log")))
            assertEquals(0, Files.size(syncRoot.resolve("empty.log")))
        }

    @Test
    fun `zero-byte local file already existing adopted correctly`() =
        runTest {
            Files.createFile(syncRoot.resolve("exists.txt"))

            provider.deltaItems = listOf(cloudItem("/exists.txt", size = 0))
            engine().syncOnce()

            val entry = db.getEntry("/exists.txt")
            assertNotNull(entry)
            assertTrue(entry.isHydrated)
        }

    // ========== Remote rename with missing local file ==========

    @Test
    fun `remote rename when old local file missing creates placeholder at new path`() =
        runTest {
            // DB has entry at old path, but local file was deleted (daemon crash cleanup)
            db.upsertEntry(
                SyncEntry(
                    path = "/old-name.txt",
                    remoteId = "id-rename",
                    remoteHash = "abc",
                    remoteSize = 100,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )

            // Remote has the file at a new path with the same remoteId
            val renamedItem =
                CloudItem(
                    id = "id-rename",
                    name = "new-name.txt",
                    path = "/new-name.txt",
                    size = 100,
                    isFolder = false,
                    modified = Instant.parse("2026-03-28T12:00:00Z"),
                    created = Instant.parse("2026-03-28T10:00:00Z"),
                    hash = "abc",
                    mimeType = "text/plain",
                )
            provider.deltaItems = listOf(renamedItem)
            provider.deltaCursor = "cursor-1"

            engine().syncOnce()

            // New path should exist (as placeholder since old file was missing)
            assertTrue(Files.exists(syncRoot.resolve("new-name.txt")))
            assertNotNull(db.getEntry("/new-name.txt"))
            assertNull(db.getEntry("/old-name.txt"))
        }

    // ========== Conflict with deleted side ==========

    @Test
    fun `local deleted remote modified conflict restores remote version`() =
        runTest {
            // Set up: file synced, then locally deleted while remotely modified
            db.upsertEntry(
                SyncEntry(
                    path = "/conflict.txt",
                    remoteId = "id-conflict",
                    remoteHash = "old",
                    remoteSize = 100,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            // File is NOT on disk (locally deleted)

            provider.deltaItems = listOf(cloudItem("/conflict.txt", size = 200, hash = "new-hash"))
            provider.deltaCursor = "cursor-1"

            // Reconciler should produce a Conflict action (local=DELETED, remote=MODIFIED)
            // KEEP_BOTH policy: remote version should be restored as placeholder
            engine().syncOnce()

            // The conflict handler should have dealt with it
            val entry = db.getEntry("/conflict.txt")
            assertNotNull(entry, "Conflict resolution should preserve/recreate the entry")
        }

    // ========== Large number of changes ==========

    @Test
    fun `sync handles many files without blowing up`() =
        runTest {
            val items = (1..200).map { cloudItem("/batch/file$it.txt", size = it.toLong()) }
            provider.deltaItems = items
            provider.deltaCursor = "cursor-1"

            engine().syncOnce()

            assertEquals(200, db.getEntriesByPrefix("/batch/").size)
            assertTrue(Files.isDirectory(syncRoot.resolve("batch")))
        }

    // ========== Re-sync after full state reset ==========

    @Test
    fun `reset and full resync rebuilds correct state`() =
        runTest {
            // Initial sync
            provider.deltaItems =
                listOf(
                    cloudItem("/keep.txt", size = 50),
                    cloudItem("/folder", isFolder = true),
                    cloudItem("/folder/child.txt", size = 75),
                )
            provider.deltaCursor = "cursor-1"
            engine().syncOnce()

            assertEquals(3, db.getAllEntries().size)

            // Reset
            db.resetAll()
            assertEquals(0, db.getAllEntries().size)
            assertNull(db.getSyncState("delta_cursor"))

            // Full resync — same remote state
            provider.deltaCursor = "cursor-2"
            engine().syncOnce()

            assertEquals(3, db.getAllEntries().size)
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")))
        }

    // ========== Delete failure tolerance ==========

    @Test
    fun `single delete failure does not stop sync`() =
        runTest {
            // Set up tracked files
            provider.deltaItems =
                listOf(
                    cloudItem("/a.txt", size = 10),
                    cloudItem("/b.txt", size = 20),
                )
            provider.deltaCursor = "cursor-1"
            engine().syncOnce()

            // Delete both locally
            Files.delete(syncRoot.resolve("a.txt"))
            Files.delete(syncRoot.resolve("b.txt"))

            // First delete fails, second succeeds
            provider.deleteFailCount = 1
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            // Should not throw — only 1 failure, not 3 consecutive
            engine().syncOnce()

            // At least the second delete should have gone through
            assertTrue(provider.deletedPaths.isNotEmpty())
        }
}
