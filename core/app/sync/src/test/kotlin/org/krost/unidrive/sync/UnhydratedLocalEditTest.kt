package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
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
import kotlin.test.assertTrue

/**
 * A row that was never hydrated (an interrupted first sync, or a file freed with `unidrive free`)
 * has a stub in the sync root: zero bytes, or zeros of the remote size, stamped with the remote
 * modification time. If the user saves real content into that stub, the scanner used to skip the
 * row (only hydrated rows are diffed), so the recovery download ran and replaced the user's bytes
 * with the remote ones: no conflict copy, no version. The edit must reach a conflict path, and a
 * stub nobody touched must still be filled in by the recovery download.
 */
class UnhydratedLocalEditTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val remoteBytes = "the bytes that live in the cloud".toByteArray()
    private val edit = "my own edit".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-297-root")
        val dbPath = Files.createTempDirectory("ud-297-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteItem(modified: Instant = remoteModified) =
        CloudItem(
            id = "id-/doc.txt",
            name = "doc.txt",
            path = "/doc.txt",
            size = remoteBytes.size.toLong(),
            isFolder = false,
            modified = modified,
            created = remoteModified,
            hash = "hash-doc",
            mimeType = null,
        )

    /**
     * An unhydrated row with the placeholder createPlaceholder leaves behind: a 0-byte file carrying
     * the remote modification time, recorded as the row's local baseline. A warm cursor, so the next
     * gather is incremental and lists nothing.
     */
    private fun unhydratedRowWithStub(): Path {
        val stub = syncRoot.resolve("doc.txt")
        Files.createFile(stub)
        Files.setLastModifiedTime(stub, FileTime.from(remoteModified))
        db.upsertEntry(
            SyncEntry(
                path = "/doc.txt",
                remoteId = "id-/doc.txt",
                remoteHash = "hash-doc",
                remoteSize = remoteBytes.size.toLong(),
                remoteModified = remoteModified,
                localMtime = remoteModified.toEpochMilli(),
                localSize = 0,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        db.setSyncState("delta_cursor", "cursor-1")
        provider.files["/doc.txt"] = remoteBytes
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
        return stub
    }

    private fun userSaves(
        file: Path,
        bytes: ByteArray,
    ) {
        Files.write(file, bytes)
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))
    }

    /** Every regular file under the sync root whose content is exactly [bytes]. */
    private fun filesHolding(bytes: ByteArray): List<Path> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && Files.readAllBytes(it).contentEquals(bytes) }.toList()
        }

    @Test
    fun `the scanner reports a stub the user wrote real content into as modified`() {
        val stub = unhydratedRowWithStub()
        userSaves(stub, edit)

        val changes = LocalScanner(syncRoot, db).scan()

        assertEquals(ChangeState.MODIFIED, changes["/doc.txt"])
    }

    @Test
    fun `a local edit saved into an unhydrated stub survives the recovery download`() =
        runTest {
            val stub = unhydratedRowWithStub()
            userSaves(stub, edit)

            engine.syncOnce()

            assertTrue(filesHolding(edit).isNotEmpty(), "the user's bytes must still exist on disk, found none under $syncRoot")
            assertContentEquals(
                remoteBytes,
                Files.readAllBytes(stub),
                "the conflict copy keeps the edit aside and the canonical path gets the remote bytes",
            )
        }

    @Test
    fun `a local edit saved into an unhydrated stub survives a remote change of the same file`() =
        runTest {
            val stub = unhydratedRowWithStub()
            userSaves(stub, edit)
            provider.deltaItems = listOf(remoteItem(modified = Instant.parse("2026-03-30T09:00:00Z")))

            engine.syncOnce()

            assertTrue(filesHolding(edit).isNotEmpty(), "the user's bytes must still exist on disk, found none under $syncRoot")
        }

    @Test
    fun `an untouched zero-byte stub is still filled in by the recovery download`() =
        runTest {
            val stub = unhydratedRowWithStub()

            engine.syncOnce()

            assertContentEquals(remoteBytes, Files.readAllBytes(stub))
            assertTrue(assertNotNull(db.getEntry("/doc.txt")).isHydrated)
        }

    @Test
    fun `a freed placeholder whose mtime a tool touched is still filled in, not treated as an edit`() =
        runTest {
            val stub = unhydratedRowWithStub()
            // What `unidrive free` leaves: zeros of the remote size. A shell handler then bumps the mtime.
            Files.write(stub, ByteArray(remoteBytes.size))
            Files.setLastModifiedTime(stub, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))

            engine.syncOnce()

            assertContentEquals(remoteBytes, Files.readAllBytes(stub))
            assertEquals(emptyList(), provider.uploadedPaths, "the zero stub must never be uploaded")
        }

    @Test
    fun `a short file at an unhydrated row is kept aside rather than uploaded over the remote`() =
        runTest {
            // Indistinguishable from a kill-truncated download prefix, and providers download to a
            // temp name and rename, so it is treated as content: kept as a conflict copy, never
            // uploaded over the remote, and the canonical path still receives the remote bytes.
            val stub = unhydratedRowWithStub()
            val prefix = remoteBytes.copyOf(10)
            userSaves(stub, prefix)

            engine.syncOnce()

            assertContentEquals(remoteBytes, Files.readAllBytes(stub))
            assertTrue(filesHolding(prefix).isNotEmpty(), "the short file must still exist on disk")
            assertFalse("/doc.txt" in provider.uploadedPaths, "a short file must not be uploaded over the remote: ${provider.uploadedPaths}")
        }
}
