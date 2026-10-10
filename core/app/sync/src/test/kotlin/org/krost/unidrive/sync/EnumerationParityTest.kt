package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.audit.AuditLog
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.EntryStatus
import java.nio.file.Files
import java.nio.file.Path
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
 * #560 U1: characterization tests for what the mirror engine ([SyncEngine]) does for the mount's
 * enumeration (`enumerateRemoteIntoState`) and the scope-transition view invalidation. They pin what
 * the code does today, so each extraction PR of #560 proves parity. The mount operations themselves
 * (`ensureHydrated`, `uploadFromCache`, `deleteRemote`, `renameRemote`, `createRemoteFolder`,
 * `remoteItemOrNull`) are pinned next to `MountEngine`, in :app:hydration's `MountOperationsParityTest`.
 */
class EnumerationParityTest {
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
