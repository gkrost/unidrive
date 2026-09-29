package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

/**
 * #396: for a provider with no remote content hash (Internxt), an mtime-only change of a
 * synced file made the next sync upload it again — a Windows shell handler bumped the mtime
 * of a just-downloaded .eml via an NTFS alternate stream, and LocalScanner had nothing to
 * compare the unchanged bytes against. The engine now records its own SHA-256 of the bytes
 * it wrote (download) or sent (upload) so the scanner can tell a touch from an edit.
 *
 * These tests drive the engine end to end with a fake hashless provider, plus a fake that
 * declares a hash algorithm (OneDrive-style) to pin that nothing is computed there.
 */
class SyncEngineLocalHashTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-396-root")
        val dbPath = Files.createTempDirectory("ud-396-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // The scanner captures provider.hashAlgorithm() at construction, so build the engine
    // only after a test has finished configuring the provider.
    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun remoteFile(
        path: String,
        size: Long,
        id: String = "id-$path",
        hash: String? = null,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = null,
    )

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    /** Initial sync that downloads [name] with [content], leaving a synced row and a stored cursor. */
    private suspend fun downloadFile(
        eng: SyncEngine,
        name: String,
        content: ByteArray,
        hash: String? = null,
    ) {
        provider.files["/$name"] = content
        provider.deltaItems = listOf(remoteFile("/$name", content.size.toLong(), hash = hash))
        eng.syncOnce()
        assertTrue(Files.exists(syncRoot.resolve(name)), "precondition: $name was downloaded")
    }

    /** Second and later syncs: nothing new on the remote side. */
    private fun quietRemote(cursor: String) {
        provider.deltaItems = emptyList()
        provider.deltaCursor = cursor
    }

    // ---- what the engine records ----

    @Test
    fun `download on a hashless provider records the SHA-256 of the placed file`() =
        runTest {
            val content = ByteArray(2048) { ((it * 7) and 0xff).toByte() }
            downloadFile(engine(), "doc.bin", content)

            val row = db.getEntry("/doc.bin")
            assertNotNull(row)
            assertEquals(sha256(syncRoot.resolve("doc.bin")), row.localHash)
            assertNull(row.remoteHash, "the fake reports no remote hash, like Internxt")
        }

    @Test
    fun `upload on a hashless provider records the SHA-256 of the sent file`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.writeString(syncRoot.resolve("local.txt"), "hello")

            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/local.txt"))
            val row = db.getEntry("/local.txt")
            assertNotNull(row?.remoteId, "the pending row was promoted by the upload")
            assertEquals(sha256(syncRoot.resolve("local.txt")), row.localHash)
        }

    @Test
    fun `an edit-and-reupload replaces the recorded hash with the new content's`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            val file = syncRoot.resolve("mod.txt")
            Files.writeString(file, "v1")
            eng.syncOnce()
            val firstHash = db.getEntry("/mod.txt")?.localHash
            assertNotNull(firstHash)

            quietRemote("cursor-2")
            Files.writeString(file, "v2-longer-content-to-change-the-size")
            eng.syncOnce()

            val row = db.getEntry("/mod.txt")
            assertNotNull(row)
            assertNotEquals(firstHash, row.localHash)
            assertEquals(sha256(file), row.localHash)
        }

    @Test
    fun `a provider with a remote hash algorithm never gets a local hash`() =
        runTest {
            provider.hashAlgorithmOverride = HashAlgorithm.QuickXor
            val eng = engine()
            downloadFile(eng, "down.bin", ByteArray(64) { it.toByte() }, hash = "remote-quickxor")
            quietRemote("cursor-2")
            Files.writeString(syncRoot.resolve("up.txt"), "hello")
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/up.txt"), "precondition: the upload happened")
            assertNull(db.getEntry("/down.bin")?.localHash, "download row: no local hash for a hash-capable provider")
            assertNull(db.getEntry("/up.txt")?.localHash, "upload row: no local hash for a hash-capable provider")
        }

    // ---- the bug itself, end to end ----

    @Test
    fun `a downloaded file whose mtime is bumped without a content change is not uploaded again`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "mail.eml", "From: someone\r\nSubject: hi\r\n\r\nbody".toByteArray())
            val file = syncRoot.resolve("mail.eml")
            val recordedMtime = db.getEntry("/mail.eml")?.localMtime
            assertNotNull(recordedMtime)

            // What the Windows property handler did: LastWriteTime jumps to "now", size and bytes stay.
            quietRemote("cursor-2")
            Files.setLastModifiedTime(file, FileTime.fromMillis(recordedMtime + 6 * 3_600_000L))
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.isEmpty(), "an mtime-only change must not become a redundant upload")
            val row = db.getEntry("/mail.eml")
            assertNotNull(row)
            assertEquals(Files.getLastModifiedTime(file).toMillis(), row.localMtime, "tracked mtime follows the touch")
            assertEquals(sha256(file), row.localHash)
        }

    @Test
    fun `a downloaded file edited to the same size with a new mtime is still uploaded`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "note.txt", "aaaa".toByteArray())
            val file = syncRoot.resolve("note.txt")
            val recordedMtime = db.getEntry("/note.txt")?.localMtime
            assertNotNull(recordedMtime)

            quietRemote("cursor-2")
            Files.writeString(file, "bbbb") // same size, one real edit
            Files.setLastModifiedTime(file, FileTime.fromMillis(recordedMtime + 6 * 3_600_000L))
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/note.txt"), "a real edit must still be uploaded")
            assertEquals(sha256(file), db.getEntry("/note.txt")?.localHash)
        }

    // ---- rows that are moved keep the hash of the (unchanged) bytes ----

    @Test
    fun `a local rename carried to the remote keeps the recorded hash`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "old.txt", "rename me".toByteArray())
            val expected = db.getEntry("/old.txt")?.localHash
            assertNotNull(expected)

            quietRemote("cursor-2")
            Files.move(syncRoot.resolve("old.txt"), syncRoot.resolve("new.txt"))
            eng.syncOnce()

            assertEquals(listOf("/old.txt" to "/new.txt"), provider.movedPaths)
            assertNull(db.getEntry("/old.txt"))
            assertEquals(expected, db.getEntry("/new.txt")?.localHash)
        }

    @Test
    fun `after a remote rename the renamed file ends up with the hash of its own bytes and is not uploaded`() =
        runTest {
            val eng = engine()
            val content = "rename me remotely".toByteArray()
            downloadFile(eng, "before.txt", content)

            // Internxt reports a rename as the same id at a new path.
            provider.files["/after.txt"] = content
            provider.deltaItems = listOf(remoteFile("/after.txt", size = content.size.toLong(), id = "id-/before.txt"))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()
            quietRemote("cursor-3")
            eng.syncOnce()

            val renamed = syncRoot.resolve("after.txt")
            assertTrue(Files.exists(renamed))
            assertFalse(Files.exists(syncRoot.resolve("before.txt")))
            assertContentEquals(content, Files.readAllBytes(renamed))
            assertEquals(sha256(renamed), db.getEntry("/after.txt")?.localHash)
            assertTrue(provider.uploadedPaths.isEmpty(), "a remote rename must never turn into an upload")
        }
}
