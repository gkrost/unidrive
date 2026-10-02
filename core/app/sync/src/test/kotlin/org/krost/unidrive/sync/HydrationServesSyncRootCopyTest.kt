package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ConflictPolicy
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
 * #449, read side: an opened file used to exist up to three times (placeholder, hydration cache, sync-root
 * copy) and a 1.4 GB file was downloaded again although an identical copy sat in the sync root. When the
 * sync-root copy is current for the row (size and mtime match the baseline, the recorded hash still matches
 * the bytes, and it is the remote version), hydration copies it into the cache instead of downloading.
 * Anything that does not prove the copy is current falls back to the download.
 */
class HydrationServesSyncRootCopyTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val content = "hello hydration, one set of bytes".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-449-root")
        cacheRoot = Files.createTempDirectory("ud-449-cache")
        db = StateDatabase(Files.createTempDirectory("ud-449-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
        engine = engineFor(provider)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engineFor(p: SyncEngineTest.FakeCloudProvider) =
        SyncEngine(
            provider = p,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
        )

    private fun remoteItem(
        path: String,
        bytes: ByteArray,
        hash: String = "hash-$path",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = bytes.size.toLong(),
        isFolder = false,
        modified = remoteModified,
        created = remoteModified,
        hash = hash,
        mimeType = null,
    )

    /** A normal sync run: the remote file lands in the sync root and the row baselines it. */
    private suspend fun syncRemoteFileDown(
        e: SyncEngine,
        path: String,
        hash: String = "hash-$path",
    ) {
        provider.files[path] = content
        provider.deltaItems = listOf(remoteItem(path, content, hash))
        e.syncOnce()
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
        provider.downloadByIdCalls.clear()
        provider.downloadByPathCalls.clear()
    }

    private fun downloads() = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size

    @Test
    fun `a current sync-root copy is served into the cache without a download`() =
        runTest {
            syncRemoteFileDown(engine, "/doc.txt")
            val cachePath = engine.resolveCachePath("/doc.txt")
            assertFalse(Files.exists(cachePath), "precondition: cold cache")

            assertEquals(cachePath, engine.ensureHydrated("/doc.txt"))

            assertEquals(0, downloads(), "the sync-root copy is current: no provider download, got ${provider.downloadByIdCalls}")
            assertContentEquals(content, Files.readAllBytes(cachePath))
            assertContentEquals(content, Files.readAllBytes(syncRoot.resolve("doc.txt")), "the sync-root file is untouched")
            val row = assertNotNull(db.getEntry("/doc.txt"))
            assertEquals(Files.getLastModifiedTime(syncRoot.resolve("doc.txt")).toMillis(), row.localMtime, "baseline still the sync-root file")
        }

    @Test
    fun `a sync-root file edited since the baseline is not served, the remote version is downloaded`() =
        runTest {
            syncRemoteFileDown(engine, "/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            Files.write(syncFile, "edited locally, other bytes".toByteArray())
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))

            val cachePath = engine.ensureHydrated("/doc.txt")

            assertEquals(1, downloads(), "a stale sync-root copy must not be served")
            assertContentEquals(content, Files.readAllBytes(cachePath), "the cache holds the remote bytes, not the local edit")
        }

    @Test
    fun `a missing sync-root file downloads`() =
        runTest {
            syncRemoteFileDown(engine, "/doc.txt")
            Files.delete(syncRoot.resolve("doc.txt"))

            val cachePath = engine.ensureHydrated("/doc.txt")

            assertEquals(1, downloads())
            assertContentEquals(content, Files.readAllBytes(cachePath))
        }

    @Test
    fun `same size and mtime but a different recorded hash downloads`() =
        runTest {
            syncRemoteFileDown(engine, "/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            val mtime = Files.getLastModifiedTime(syncFile)
            // Bit rot or a tool that rewrote the bytes and restored the mtime: same size, same mtime.
            val corrupted = ByteArray(content.size) { 'x'.code.toByte() }
            Files.write(syncFile, corrupted)
            Files.setLastModifiedTime(syncFile, mtime)
            assertNotNull(db.getEntry("/doc.txt")?.localHash, "precondition: a local hash was recorded (#396)")

            val cachePath = engine.ensureHydrated("/doc.txt")

            assertEquals(1, downloads(), "the recorded hash no longer matches the bytes: do not serve them")
            assertContentEquals(content, Files.readAllBytes(cachePath))
        }

    @Test
    fun `a row whose remote changed size since the baseline downloads`() =
        runTest {
            syncRemoteFileDown(engine, "/doc.txt")
            val newer = "the remote moved on and grew".toByteArray()
            provider.files["/doc.txt"] = newer
            // An enumeration refreshed the remote side of the row; the sync-root file still matches its baseline.
            val row = assertNotNull(db.getEntry("/doc.txt"))
            db.upsertEntry(row.copy(remoteSize = newer.size.toLong(), remoteModified = Instant.parse("2026-04-02T00:00:00Z")))

            val cachePath = engine.ensureHydrated("/doc.txt")

            assertEquals(1, downloads(), "the sync-root copy is an older version than the remote")
            assertContentEquals(newer, Files.readAllBytes(cachePath))
        }

    @Test
    fun `with a content hash provider the copy is served only when it matches the remote hash`() =
        runTest {
            val hashing = SyncEngineTest.FakeCloudProvider().also { it.hashAlgorithmOverride = HashAlgorithm.Sha256Hex }
            val hashEngine = engineFor(hashing)
            val sha = HashVerifier.computeSha256Hex(Files.createTempFile("ud-449", ".bin").also { Files.write(it, content) })
            hashing.files["/doc.txt"] = content
            hashing.deltaItems = listOf(remoteItem("/doc.txt", content, hash = sha))
            hashEngine.syncOnce()
            hashing.downloadByIdCalls.clear()
            hashing.downloadByPathCalls.clear()

            hashEngine.ensureHydrated("/doc.txt")
            assertEquals(0, hashing.downloadByIdCalls.size + hashing.downloadByPathCalls.size, "matching remote hash: served from the sync root")

            // The remote now has other bytes (hash changed) although size and mtime of the row's baseline still match.
            Files.deleteIfExists(hashEngine.resolveCachePath("/doc.txt"))
            val row = assertNotNull(db.getEntry("/doc.txt"))
            db.upsertEntry(row.copy(remoteHash = "0".repeat(64)))
            hashing.files["/doc.txt"] = ByteArray(content.size) { 'y'.code.toByte() }

            hashEngine.ensureHydrated("/doc.txt")
            assertEquals(1, hashing.downloadByIdCalls.size + hashing.downloadByPathCalls.size, "remote hash differs from the sync-root bytes: download")
            assertTrue(Files.readAllBytes(hashEngine.resolveCachePath("/doc.txt")).all { it == 'y'.code.toByte() })
        }
}
