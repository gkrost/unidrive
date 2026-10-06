package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.audit.AuditLog
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.EntryStatus
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #560 U1: characterization tests for the mount-only operations that still live in [SyncEngine]
 * (`ensureHydrated`, `uploadFromCache`, `deleteRemote`, `renameRemote`, `createRemoteFolder`,
 * `remoteItemOrNull`, `enumerateRemoteIntoState`, `rescanSyncRootForUpload`) and the scope-transition
 * view invalidation. They pin what the code does today, so each extraction PR of #560 proves parity.
 * Only behaviour no other test pins is covered here; a test whose name starts with "current behaviour"
 * pins something a later unit may change on purpose.
 */
class MountOperationsParityTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val content = "bytes the cloud holds".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-560-root")
        cacheRoot = Files.createTempDirectory("ud-560-cache")
        db = StateDatabase(Files.createTempDirectory("ud-560-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        scope: List<String> = emptyList(),
        auditLog: AuditLog? = null,
        root: Path = syncRoot,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = root,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = ProgressReporter.Silent,
        cacheRoot = cacheRoot,
        syncPaths = scope,
        standingScope = scope,
        auditLog = auditLog,
        viewInvalidationSink = { paths, full, _moved -> invalidations += paths to full },
    )

    private fun remoteItem(
        path: String,
        bytes: ByteArray = content,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = if (isFolder) 0 else bytes.size.toLong(),
        isFolder = isFolder,
        modified = remoteModified,
        created = remoteModified,
        hash = if (isFolder) null else "hash-$path",
        mimeType = null,
    )

    /** A plain sync puts the remote file into the sync root; the row then describes that file. */
    private suspend fun syncDown(
        e: SyncEngine,
        path: String,
    ) {
        provider.files[path] = content
        provider.deltaItems = listOf(remoteItem(path))
        e.syncOnce()
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
        provider.downloadByIdCalls.clear()
        provider.downloadByPathCalls.clear()
    }

    private fun downloads() = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size

    private fun remoteRow(
        path: String,
        hydrated: Boolean = false,
        size: Long = content.size.toLong(),
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "hash-$path",
        remoteSize = size,
        remoteModified = remoteModified,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    /** The row `hydration.create` writes: never uploaded, its bytes only in the cache. */
    private fun mountCreatedRow(path: String) =
        SyncEntry(
            path = path,
            remoteId = null,
            remoteHash = null,
            remoteSize = 0L,
            remoteModified = null,
            localMtime = System.currentTimeMillis(),
            localSize = 0L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun writeCache(
        e: SyncEngine,
        path: String,
        bytes: ByteArray,
    ): Path =
        e.resolveCachePath(path).also {
            Files.createDirectories(it.parent)
            Files.write(it, bytes)
        }

    // ── ensureHydrated: which local file the row describes afterwards (cacheBacked) ──────────

    @Test
    fun `ensureHydrated download without a sync-root file records the cache copy as the row's local file`() =
        runTest {
            val e = engine()
            provider.files["/cold.txt"] = content
            db.upsertEntry(remoteRow("/cold.txt"))

            val cache = e.ensureHydrated("/cold.txt")

            assertEquals(1, downloads())
            val row = assertNotNull(db.getEntry("/cold.txt"))
            assertEquals(true, row.cacheBacked, "the cache copy is the row's local file")
            assertTrue(row.isHydrated)
            assertEquals(Files.getLastModifiedTime(cache).toMillis(), row.localMtime)
            assertEquals(content.size.toLong(), row.localSize)
        }

    @Test
    fun `ensureHydrated served from a current sync-root copy marks the row as describing the sync-root file`() =
        runTest {
            val e = engine()
            syncDown(e, "/doc.txt")

            e.ensureHydrated("/doc.txt")

            assertEquals(0, downloads(), "precondition: served from the sync root (copySyncRootCopyIntoCache)")
            assertEquals(false, db.getEntry("/doc.txt")?.cacheBacked)
        }

    @Test
    fun `ensureHydrated download for a row that describes the sync-root file keeps that file as the baseline`() =
        runTest {
            val e = engine()
            syncDown(e, "/doc.txt")
            val baseline = assertNotNull(db.getEntry("/doc.txt"))
            // The remote moved on (an enumeration refreshed the remote side); the sync-root file still matches the baseline.
            val newer = "the remote moved on and grew".toByteArray()
            provider.files["/doc.txt"] = newer
            db.upsertEntry(baseline.copy(remoteSize = newer.size.toLong()))

            e.ensureHydrated("/doc.txt")

            assertEquals(1, downloads())
            val row = assertNotNull(db.getEntry("/doc.txt"))
            assertEquals(false, row.cacheBacked)
            assertEquals(baseline.localMtime, row.localMtime, "the baseline is still the sync-root file")
            assertEquals(baseline.localSize, row.localSize)
            assertEquals(newer.size.toLong(), row.remoteSize, "the download refreshes remoteSize")
        }

    @Test
    fun `ensureHydrated warm path settles a legacy row that describes the sync-root file and leaves a cache-only one unknown`() =
        runTest {
            val e = engine()
            syncDown(e, "/doc.txt")
            e.ensureHydrated("/doc.txt")
            db.upsertEntry(assertNotNull(db.getEntry("/doc.txt")).copy(cacheBacked = null))

            e.ensureHydrated("/doc.txt")
            assertEquals(false, db.getEntry("/doc.txt")?.cacheBacked, "a legacy row whose baseline is the sync-root file is settled")

            // A cache-only legacy row (no sync-root file): the warm path leaves it alone.
            writeCache(e, "/cache-only.txt", content)
            db.upsertEntry(remoteRow("/cache-only.txt", hydrated = true).copy(cacheBacked = null))
            e.ensureHydrated("/cache-only.txt")
            assertNull(db.getEntry("/cache-only.txt")?.cacheBacked)
            assertEquals(0, downloads(), "both were warm")
        }

    // ── cancellation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cancelled re-hydration leaves the row and the previous cache copy as they were`() =
        runTest {
            val e = engine()
            provider.files["/big.bin"] = content
            provider.downloadDelayMs = 60_000
            val row = remoteRow("/big.bin", hydrated = true)
            db.upsertEntry(row)
            // A truncated warm cache: ensureHydrated re-downloads.
            val cache = writeCache(e, "/big.bin", "trunc".toByteArray())

            var thrown: Throwable? = null
            val job = launch {
                try {
                    e.ensureHydrated("/big.bin")
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            runCurrent()
            assertEquals(1, provider.downloadByIdCalls.size, "precondition: the download is in flight")
            job.cancel()
            job.join()

            assertTrue(thrown is CancellationException, "cancellation propagates as CancellationException, got $thrown")
            assertEquals(row, db.getEntry("/big.bin"), "the row is untouched")
            assertContentEquals("trunc".toByteArray(), Files.readAllBytes(cache), "the previous cache copy is untouched")
            Files.list(cache.parent).use { s ->
                assertEquals(listOf("big.bin"), s.map { it.fileName.toString() }.toList(), "no staging file is left")
            }
        }

    @Test
    fun `current behaviour - a cancelled uploadFromCache rethrows, is audited as failed and leaves the row pending`() =
        runTest {
            val audit = AuditLog(Files.createTempDirectory("ud-560-audit"), profileName = "parity")
            val e = engine(auditLog = audit)
            val created = mountCreatedRow("/new.txt")
            db.upsertEntry(created)
            val cache = writeCache(e, "/new.txt", "mount bytes".toByteArray())
            provider.duringUpload = { throw CancellationException("cancelled mid-transfer") }

            assertFailsWith<CancellationException> { e.uploadFromCache("/new.txt", cache) }

            assertEquals(created, db.getEntry("/new.txt"), "the row stays a pending upload")
            assertTrue(Files.exists(cache), "the cache copy stays")
            assertFalse(Files.exists(syncRoot.resolve("new.txt")), "nothing is mirrored into the sync root")
            val lines = Files.readAllLines(audit.pathForToday()).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("failed:CancellationException"), lines[0])
        }

    @Test
    fun `a cancelled rescan rethrows and releases its single-flight guard`() =
        runTest {
            val e = engine()
            Files.writeString(syncRoot.resolve("a.txt"), "x")
            provider.duringUpload = { throw CancellationException("daemon stopping") }

            assertFailsWith<CancellationException> { e.rescanSyncRootForUpload() }

            provider.duringUpload = null
            val next = e.rescanSyncRootForUpload()
            assertFalse(next.notRun, "the guard was released by the cancelled pass")
            assertEquals(1, next.uploaded)
        }

    // ── uploadFromCache failure ──────────────────────────────────────────────────────────────

    @Test
    fun `a failed uploadFromCache leaves the row, the cache copy and the sync root untouched`() =
        runTest {
            val e = engine()
            val created = mountCreatedRow("/new.txt")
            db.upsertEntry(created)
            val cache = writeCache(e, "/new.txt", "mount bytes".toByteArray())
            provider.uploadFailCount = 1

            val thrown = assertFailsWith<ProviderException> { e.uploadFromCache("/new.txt", cache) }

            assertEquals("Network timeout on upload", thrown.message, "the provider's exception propagates unchanged")
            assertEquals(created, db.getEntry("/new.txt"))
            assertTrue(db.pendingUploadPaths().contains("/new.txt"), "still a pending upload")
            assertContentEquals("mount bytes".toByteArray(), Files.readAllBytes(cache))
            assertFalse(Files.exists(syncRoot.resolve("new.txt")))
        }

    // ── deleteRemote / renameRemote / createRemoteFolder ─────────────────────────────────────

    @Test
    fun `deleteRemote of a folder tombstones every row below it and nothing beside it`() =
        runTest {
            val e = engine()
            db.insertFolder("/d", "id-/d", Instant.now())
            db.upsertEntry(remoteRow("/d/x.txt"))
            db.insertFolder("/d/sub", "id-/d/sub", Instant.now())
            db.upsertEntry(remoteRow("/d/sub/y.txt"))
            db.upsertEntry(remoteRow("/dx/z.txt"))

            e.deleteRemote("/d")

            assertEquals(listOf("/d"), provider.deletedPaths, "one remote delete, the folder itself")
            for (p in listOf("/d", "/d/x.txt", "/d/sub", "/d/sub/y.txt")) {
                assertNull(db.getEntry(p), "$p is no longer alive")
                assertEquals(EntryStatus.DELETED, db.statusOf(p), "$p is tombstoned, not dropped")
            }
            assertNotNull(db.getEntry("/dx/z.txt"), "a same-prefix sibling is not below the folder")
        }

    // deleteRemote and the sync-root copy of a deleted file: found here as a bug (an unsynced edit the mirror skipped
    // was deleted with the remote item) and fixed in #568, whose tests in UploadFromCacheKeepsWriteTest pin the
    // corrected behaviour. Deliberately not pinned here, so the two land in either order.

    @Test
    fun `renameRemote of a folder repaths every row below it`() =
        runTest {
            val e = engine()
            db.insertFolder("/f", "id-/f", Instant.now())
            db.upsertEntry(remoteRow("/f/a.txt"))
            db.insertFolder("/f/sub", "id-/f/sub", Instant.now())
            db.upsertEntry(remoteRow("/f/sub/b.txt"))

            e.renameRemote("/f", "/g")

            assertEquals(listOf("/f" to "/g"), provider.movedPaths)
            for (p in listOf("/g", "/g/a.txt", "/g/sub", "/g/sub/b.txt")) assertNotNull(db.getEntry(p), p)
            for (p in listOf("/f", "/f/a.txt", "/f/sub/b.txt")) assertNull(db.getEntry(p), p)
        }

    @Test
    fun `renameRemote moves the sync-root file only for a row whose baseline is that file`() =
        runTest {
            val e = engine()
            for ((name, backed) in listOf("cache.txt" to true, "legacy.txt" to null)) {
                Files.writeString(syncRoot.resolve(name), "local $name")
                db.upsertEntry(remoteRow("/$name", hydrated = true).copy(cacheBacked = backed))

                e.renameRemote("/$name", "/moved-$name")

                assertTrue(Files.exists(syncRoot.resolve(name)), "cacheBacked=$backed: the sync-root file stays where it was")
                assertFalse(Files.exists(syncRoot.resolve("moved-$name")))
            }
        }

    @Test
    fun `renameRemote never replaces a file at the destination in the sync root`() =
        runTest {
            val e = engine()
            Files.writeString(syncRoot.resolve("a.txt"), "source")
            Files.writeString(syncRoot.resolve("b.txt"), "already there")
            db.upsertEntry(remoteRow("/a.txt", hydrated = true).copy(cacheBacked = false))

            e.renameRemote("/a.txt", "/b.txt")

            assertEquals("source", Files.readString(syncRoot.resolve("a.txt")), "the old copy stays, the next sync decides")
            assertEquals("already there", Files.readString(syncRoot.resolve("b.txt")))
            assertNotNull(db.getEntry("/b.txt"), "the row moved anyway")
        }

    @Test
    fun `createRemoteFolder that the provider refuses writes no row and no sync-root folder`() =
        runTest {
            val e = engine()
            provider.createFolderFailPaths.add("/newdir")

            assertFailsWith<ProviderException> { e.createRemoteFolder("/newdir") }

            assertNull(db.getEntry("/newdir"))
            assertFalse(Files.exists(syncRoot.resolve("newdir")))
        }

    @Test
    fun `createRemoteFolder records the provider's id`() =
        runTest {
            val e = engine()
            val item = e.createRemoteFolder("/newdir")
            val row = assertNotNull(db.getEntry("/newdir"))
            assertEquals(item.id, row.remoteId)
            assertTrue(row.isFolder)
            assertTrue(Files.isDirectory(syncRoot.resolve("newdir")))
        }

    // ── remoteItemOrNull ─────────────────────────────────────────────────────────────────────

    @Test
    fun `remoteItemOrNull maps only a proven absence to null`() =
        runTest {
            val e = engine()
            provider.deltaItems = listOf(remoteItem("/here.txt"))
            assertEquals("id-/here.txt", e.remoteItemOrNull("/here.txt")?.id)

            val absent =
                listOf(
                    ProviderException("Item not found: /x"),
                    ProviderException("Folder not found: a in /a/x"),
                    ParityStatusCodeException(404),
                    RuntimeException("wrapped", ParityStatusCodeException(404)),
                )
            for (error in absent) {
                provider.getMetadataError = error
                assertNull(e.remoteItemOrNull("/x"), "absence: $error")
            }

            val transient =
                listOf(
                    ParityStatusCodeException(500),
                    ProviderException("503 Service Unavailable: item not found in cache"),
                    java.io.IOException("connection reset"),
                    org.krost.unidrive.AuthenticationException("Token expired"),
                )
            for (error in transient) {
                provider.getMetadataError = error
                val thrown = assertFailsWith<Exception> { e.remoteItemOrNull("/x") }
                assertSame(error, thrown, "a non-absence failure propagates unchanged")
            }
        }

    // ── enumerateRemoteIntoState ─────────────────────────────────────────────────────────────

    @Test
    fun `a failed gather with reset keeps every row, leaves the cursor cleared and invalidates nothing`() =
        runTest {
            val e = engine()
            provider.files["/a.txt"] = content
            provider.deltaItems = listOf(remoteItem("/a.txt"), remoteItem("/b", isFolder = true))
            assertTrue(e.enumerateRemoteIntoState(reset = false).ok)
            val before = db.getAllEntries().associateBy { it.path }
            assertEquals(setOf("/a.txt", "/b"), before.keys)
            invalidations.clear()
            provider.deltaFailCount = 1

            val r = e.enumerateRemoteIntoState(reset = true)

            assertFalse(r.ok)
            assertEquals("Network timeout on delta", r.error)
            assertEquals(before, db.getAllEntries().associateBy { it.path }, "a failed gather keeps the last good rows")
            assertEquals("", db.getSyncState("delta_cursor"), "reset cleared the cursor, the next gather is a full one")
            assertEquals(emptyList(), invalidations, "nothing changed, nothing is invalidated")
        }

    @Test
    fun `a reset enumeration sweeps a row the remote no longer lists without clearing the others`() =
        runTest {
            val e = engine()
            provider.deltaItems = listOf(remoteItem("/keep.txt"), remoteItem("/gone.txt"))
            e.enumerateRemoteIntoState(reset = false)
            provider.deltaItems = listOf(remoteItem("/keep.txt"))
            provider.deltaCursor = "cursor-2"

            val r = e.enumerateRemoteIntoState(reset = true)

            assertTrue(r.ok && r.complete)
            assertEquals(1, r.reaped)
            assertEquals(EntryStatus.DELETED, db.statusOf("/gone.txt"))
            assertNotNull(db.getEntry("/keep.txt"))
            assertTrue(provider.deletedPaths.isEmpty(), "a reap never deletes on the remote")
        }

    // ── scope transition (viewInvalidationSink, full = true) ─────────────────────────────────

    @Test
    fun `widening the standing scope invalidates the whole view once`() =
        runTest {
            provider.deltaItems = listOf(remoteItem("/a", isFolder = true), remoteItem("/b", isFolder = true))
            engine(scope = listOf("/a")).enumerateRemoteIntoState(reset = false)
            invalidations.clear()

            engine(scope = listOf("/a", "/b")).enumerateRemoteIntoState(reset = false)

            assertEquals(listOf(emptySet<String>() to true), invalidations.filter { it.second })
            assertNotNull(db.getEntry("/b"), "the widened subtree is enumerated")
        }

    @Test
    fun `a sync pass that narrows the standing scope invalidates the whole view once`() =
        runTest {
            provider.files["/a/x.txt"] = content
            provider.files["/b/y.txt"] = content
            provider.deltaItems =
                listOf(
                    remoteItem("/a", isFolder = true),
                    remoteItem("/a/x.txt"),
                    remoteItem("/b", isFolder = true),
                    remoteItem("/b/y.txt"),
                )
            engine().syncOnce()
            assertTrue(invalidations.none { it.second }, "an unscoped first pass over an empty scope record is no transition")

            engine(scope = listOf("/a")).syncOnce()

            assertEquals(listOf(emptySet<String>() to true), invalidations.filter { it.second })
            assertNull(db.getEntry("/b/y.txt"))
        }

    @Test
    fun `a sync pass with an unchanged scope does not invalidate the whole view`() =
        runTest {
            provider.deltaItems = listOf(remoteItem("/a", isFolder = true))
            engine(scope = listOf("/a")).syncOnce()
            invalidations.clear()

            engine(scope = listOf("/a")).syncOnce()

            assertTrue(invalidations.none { it.second }, "got $invalidations")
        }
}

/** A provider exception that carries an HTTP status the way OneDrive's GraphApiException does (`getStatusCode()`). */
internal class ParityStatusCodeException(
    val statusCode: Int,
) : RuntimeException("HTTP $statusCode")
