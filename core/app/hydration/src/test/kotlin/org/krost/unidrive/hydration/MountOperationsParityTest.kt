package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.audit.AuditLog
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #560 U1: characterization tests for the mount operations (`ensureHydrated`, `uploadFromCache`,
 * `deleteRemote`, `renameRemote`, `createRemoteFolder`, `remoteItemOrNull`), which moved from
 * `SyncEngine` to [MountEngine] in U3. They run on the mount front-end of a `SyncEngine` host (the
 * production wiring, `MountEngine.over`) and pin what the code does today, so each extraction PR of
 * #560 proves parity. Only behaviour no other test pins is covered here; a test whose name starts
 * with "current behaviour" pins something a later unit may change on purpose. The enumeration and
 * scope-transition parity of the mirror engine stays in :app:sync (`EnumerationParityTest`).
 */
class MountOperationsParityTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val content = "bytes the cloud holds".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-560-root")
        cacheRoot = Files.createTempDirectory("ud-560-cache")
        db = StateDatabase(Files.createTempDirectory("ud-560-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
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
            val auditDir = Files.createTempDirectory("ud-560-audit")
            val audit = AuditLog(auditDir, profileName = "parity")
            val e = engine(auditLog = audit)
            val created = mountCreatedRow("/new.txt")
            db.upsertEntry(created)
            val cache = writeCache(e, "/new.txt", "mount bytes".toByteArray())
            provider.duringUpload = { throw CancellationException("cancelled mid-transfer") }

            assertFailsWith<CancellationException> { e.uploadFromCache("/new.txt", cache) }

            assertEquals(created, db.getEntry("/new.txt"), "the row stays a pending upload")
            assertTrue(Files.exists(cache), "the cache copy stays")
            val lines = Files.readAllLines(auditFileIn(auditDir)).filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("failed:CancellationException"), lines[0])
        }

    // ── uploadFromCache failure ──────────────────────────────────────────────────────────────

    @Test
    fun `a failed uploadFromCache leaves the row and the cache copy untouched`() =
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
        }

    // ── deleteRemote / renameRemote / createRemoteFolder ─────────────────────────────────────

    // #560 U6: a remote delete no longer touches a file left at the legacy sync-root path —
    // the mount owns no local folder, so a leftover from the coordinated model stays untouched.
    @Test
    fun `deleteRemote leaves a file at the legacy sync-root path alone`() =
        runTest {
            val e = engine()
            db.upsertEntry(remoteRow("/gone.txt"))
            Files.createDirectories(syncRoot)
            Files.writeString(syncRoot.resolve("gone.txt"), "left over from the coordinated model")

            e.deleteRemote("/gone.txt")

            assertTrue(provider.deletedPaths.contains("/gone.txt"), "the remote item is deleted")
            assertTrue(Files.exists(syncRoot.resolve("gone.txt")), "the mount touches no sync root")
        }

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
    fun `createRemoteFolder that the provider refuses writes no row`() =
        runTest {
            val e = engine()
            provider.createFolderFailPaths.add("/newdir")

            assertFailsWith<ProviderException> { e.createRemoteFolder("/newdir") }

            assertNull(db.getEntry("/newdir"))
        }

    @Test
    fun `createRemoteFolder records the provider's id`() =
        runTest {
            val e = engine()
            val item = e.createRemoteFolder("/newdir")
            val row = assertNotNull(db.getEntry("/newdir"))
            assertEquals(item.id, row.remoteId)
            assertTrue(row.isFolder)
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
}

/** A provider exception that carries an HTTP status the way OneDrive's GraphApiException does (`getStatusCode()`). */
internal class ParityStatusCodeException(
    val statusCode: Int,
) : RuntimeException("HTTP $statusCode")
