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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #739: a file with no state row cannot be an engine placeholder (the engine's placeholders are
 * 0-byte files, and a freed or interrupted one always has a row). A real file that happens to be all
 * zeros (a preallocated database, a fresh disk image) used to be taken for a sparse stub, recorded as
 * not hydrated, and after a failed first upload no recovery loop picked it up again: its bytes never
 * reached the cloud. A stub that does have a row is still recognised as one.
 */
class AllZeroNewFileTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val size = 4 * 1024 * 1024

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-739-root")
        db = StateDatabase(Files.createTempDirectory("ud-739-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
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
    fun `an all-zero new file whose first upload fails is uploaded on the next pass`() =
        runTest {
            val zeros = syncRoot.resolve("zeros.bin")
            Files.write(zeros, ByteArray(size))
            val engine = engine()

            // The first pass cannot reach the cloud.
            provider.uploadFailCount = 1_000
            engine.syncOnce()
            assertNull(provider.files["/zeros.bin"], "precondition: the first upload failed")

            // The next pass retries it.
            provider.uploadFailCount = 0
            engine.syncOnce()

            assertContentEquals(ByteArray(size), provider.files["/zeros.bin"], "the all-zero file reached the cloud")
            assertContentEquals(ByteArray(size), Files.readAllBytes(zeros), "and is unchanged on disk")
            val row = assertNotNull(db.getEntry("/zeros.bin"))
            assertTrue(row.isHydrated)
            assertNotNull(row.remoteId)
        }

    @Test
    fun `the scanner records a new all-zero file as hydrated`() {
        Files.write(syncRoot.resolve("zeros.bin"), ByteArray(size))

        LocalScanner(syncRoot, db).scan()

        val row = assertNotNull(db.getEntry("/zeros.bin"))
        assertTrue(row.isHydrated, "a file with no row is real content, whatever its bytes")
        assertTrue(row.isPendingUpload)
    }

    @Test
    fun `a freed placeholder with a row is still a stub, not an edit to upload`() =
        runTest {
            // What `unidrive free` leaves: zeros of the remote size, with an existing, unhydrated row.
            // A tool bumps its mtime afterwards.
            val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
            val remoteBytes = ByteArray(size) { (it % 251 + 1).toByte() }
            val stub = syncRoot.resolve("big.bin")
            Files.write(stub, ByteArray(size))
            Files.setLastModifiedTime(stub, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))
            db.upsertEntry(
                SyncEntry(
                    path = "/big.bin",
                    remoteId = "id-/big.bin",
                    remoteHash = "hash-big",
                    remoteSize = size.toLong(),
                    remoteModified = remoteModified,
                    localMtime = remoteModified.toEpochMilli(),
                    localSize = size.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = remoteModified,
                ),
            )
            db.setSyncState("delta_cursor", "cursor-1")
            provider.files["/big.bin"] = remoteBytes
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            assertTrue(PlaceholderManager(syncRoot).isSparse(stub, size.toLong()), "the stub probe still reports it")
            assertFalse("/big.bin" in LocalScanner(syncRoot, db).scan(), "the scanner does not report the stub as a change")

            engine().syncOnce()

            assertContentEquals(remoteBytes, Files.readAllBytes(stub), "the recovery download fills the stub")
            assertEquals(emptyList(), provider.uploadedPaths, "the stub is never uploaded")
        }
}
