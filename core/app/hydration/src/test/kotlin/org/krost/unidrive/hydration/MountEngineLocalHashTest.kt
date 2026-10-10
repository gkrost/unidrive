package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

/**
 * #396: the local hash the mount operations record (moved here from `SyncEngineLocalHashTest`
 * with the operations, #560 U3). A write-back through the cache and a mount-mode re-download must
 * record the hash of the bytes they just sent or placed, so LocalScanner's touch check never pairs
 * stale bytes with fresh stats. Driven end to end on the mount front-end of a `SyncEngine` host with
 * a fake hashless provider.
 */
class MountEngineLocalHashTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-396-root")
        val dbPath = Files.createTempDirectory("ud-396-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // The scanner captures provider.hashAlgorithm() at construction, so build the engine
    // only after a test has finished configuring the provider.
    private fun engine(cacheRoot: Path? = null) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
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

    // #337: an upload row takes the file's stats after the transfer, so an edit that lands
    // mid-upload is already in the baseline. Its hash must not be recorded too.
    private fun editDuringUpload(edited: ByteArray) {
        provider.duringUpload = { path ->
            Files.write(path, edited)
            Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 60_000L))
            provider.duringUpload = null
        }
    }

    @Test
    fun `a write-back through the cache records the hash of the bytes it just uploaded`() =
        runTest {
            // uploadFromCache is the FUSE write-back path: the bytes live in the daemon's
            // cache copy, and the row records exactly that copy's stats, so its hash must be
            // those bytes' hash. Leaving it null (or keeping a stale one) would strip the
            // touch shield from every mount-edited file.
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            // #319: the write-back requires the row the FUSE create flow wrote
            // (HydrationImpl.create) — a row-less upload is refused so it cannot
            // resurrect a vanished path. Seed the never-uploaded row here.
            seedLocalOnlyRow("/local.txt")
            val cacheCopy = Files.createTempDirectory("ud-396-wb").resolve("local.txt")
            val bytes = "written through the mount".toByteArray()
            Files.write(cacheCopy, bytes)

            eng.uploadFromCache("/local.txt", cacheCopy)

            val row = db.getEntry("/local.txt")
            assertNotNull(row?.remoteId, "the write-back was uploaded")
            assertEquals(sha256(cacheCopy), row.localHash)
        }

    // The row HydrationImpl.create writes for a file created through the mount:
    // never uploaded, cache holds the only copy.
    private fun seedLocalOnlyRow(path: String) {
        db.upsertEntry(
            SyncEntry(
                path = path,
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
    }

    @Test
    fun `a mount-mode re-download records the hash of the downloaded bytes instead of keeping the stale one`() =
        runTest {
            // ensureHydrated rewrites the row's local stats from the freshly downloaded cache
            // copy; keeping a hash recorded for the previous contents would pair stale bytes
            // with a fresh mtime/size — the stale-hash shape the touch check must never see.
            val cacheRoot = Files.createTempDirectory("ud-396-hyd-cache")
            val eng = engine(cacheRoot)
            val first = ByteArray(1024) { ((it * 3) and 0xff).toByte() }
            downloadFile(eng, "doc.bin", first)
            // Mount mode: the row is served from the daemon cache and nothing is in the sync
            // root (a row describing a sync-root file keeps its hash in ensureHydrated — the
            // sync-root bytes are unchanged, so the recorded hash still describes them).
            db.upsertEntry(assertNotNull(db.getEntry("/doc.bin")).copy(localMtime = null, localSize = null))
            Files.deleteIfExists(syncRoot.resolve("doc.bin"))
            val staleHash = assertNotNull(db.getEntry("/doc.bin")).localHash
            assertNotNull(staleHash)

            val second = "fresh remote bytes for the re-download".toByteArray()
            provider.files["/doc.bin"] = second
            val cachePath = eng.resolveCachePath("/doc.bin")
            Files.deleteIfExists(cachePath)
            eng.ensureHydrated("/doc.bin")

            val row = assertNotNull(db.getEntry("/doc.bin"))
            assertEquals(sha256(cachePath), row.localHash)
            assertNotEquals(staleHash, row.localHash)
        }

    @Test
    fun `a write-back whose cache copy changes during the upload records no hash`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            // #319: the write-back requires the row the FUSE create flow wrote.
            seedLocalOnlyRow("/local.txt")
            val cacheCopy = Files.createTempDirectory("ud-396-wb-race").resolve("local.txt")
            Files.writeString(cacheCopy, "first version")
            editDuringUpload("edited version".toByteArray())

            eng.uploadFromCache("/local.txt", cacheCopy)

            val row = assertNotNull(db.getEntry("/local.txt"))
            assertNotNull(row.remoteId, "the write-back was uploaded")
            assertNull(row.localHash)
        }
}
