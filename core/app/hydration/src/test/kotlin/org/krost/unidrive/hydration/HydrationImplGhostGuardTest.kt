package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ghost-guard for the mount's rename/unlink (#203 layer-2). A state.db row with a
// local: synthetic id is normally a genuinely-never-uploaded file. But a "ghost" —
// an upload that committed cloud-side but lost its response — also presents as
// local:. Without the guard, renaming/deleting a ghost takes the local-only branch
// and silently skips the cloud move/delete (the reported XnViewMP symptom).
//
// If these tests are removed or loosened, a ghost rename/delete again fails to
// reach the cloud and the local: row risks a duplicate re-upload. Invariants:
//  1. ghost rename     -> real cloud move + adopt the real id (row no longer local:).
//  2. ghost unlink     -> real cloud delete.
//  3. genuinely-local  -> remote-absent probe -> local-only rename, no cloud move.
class HydrationImplGhostGuardTest {

    private class GhostFakeProvider : CloudProvider {
        override val id = "fake-ghost"
        override val displayName = "Fake (ghost test)"
        override var isAuthenticated = true

        // Path that is actually present on the cloud despite a local: row locally.
        var ghostAt: String? = null
        val ghostId = "real-ghost-uuid"
        var lastMoveFrom: String? = null
        var lastMoveTo: String? = null
        var lastDeletePath: String? = null

        // When set, getMetadata throws this (a transient, NOT a not-found) so the
        // probe must propagate rather than be read as absence.
        var transientError: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem {
            transientError?.let { throw it }
            return if (path == ghostAt) {
                CloudItem(
                    id = ghostId,
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = 4242L,
                    isFolder = false,
                    modified = Instant.parse("2026-05-24T09:00:00Z"),
                    created = null,
                    hash = "ghost-hash",
                    mimeType = null,
                )
            } else {
                throw ProviderException("Item not found: $path")
            }
        }

        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")

        override suspend fun delete(remotePath: String) {
            lastDeletePath = remotePath
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            lastMoveFrom = fromPath
            lastMoveTo = toPath
            return CloudItem(
                id = ghostId,
                name = toPath.substringAfterLast('/'),
                path = toPath,
                size = 4242L,
                isFolder = false,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = "ghost-hash",
                mimeType = null,
            )
        }

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun freshEnv(): Triple<HydrationImpl, GhostFakeProvider, StateDatabase> {
        val cacheRoot = Files.createTempDirectory("ud-ghost-cache")
        val dbPath = Files.createTempDirectory("ud-ghost-db").resolve("state.db")
        val provider = GhostFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("ud-ghost-sync")
        val engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db)
        return Triple(hydration, provider, db)
    }

    private fun seedLocalGhostRow(db: StateDatabase, path: String) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null, // stored as a local: synthetic
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = 4242L,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `ghost rename moves on remote and adopts the real id`() =
        runTest {
            val (hydration, provider, db) = freshEnv()
            seedLocalGhostRow(db, "/ghost.deb")
            provider.ghostAt = "/ghost.deb" // it IS on the cloud despite the local: row

            val result = hydration.rename("/ghost.deb", "/renamed.deb")

            assertEquals(RenameResult.Ok, result)
            assertEquals("/ghost.deb", provider.lastMoveFrom, "ghost rename must move on the cloud")
            assertEquals("/renamed.deb", provider.lastMoveTo)
            assertNull(db.getEntry("/ghost.deb"), "old path row gone")
            val moved = db.getEntry("/renamed.deb")
            assertNotNull(moved, "row present at the new path")
            assertEquals(provider.ghostId, moved.remoteId, "row must adopt the real remote id (no longer a local: ghost)")
        }

    @Test
    fun `ghost unlink deletes on remote`() =
        runTest {
            val (hydration, provider, db) = freshEnv()
            seedLocalGhostRow(db, "/ghost.deb")
            provider.ghostAt = "/ghost.deb"

            val result = hydration.unlink("/ghost.deb")

            assertEquals(UnlinkResult.Ok, result)
            assertEquals("/ghost.deb", provider.lastDeletePath, "ghost unlink must delete the cloud copy, not just drop the row")
        }

    @Test
    fun `genuinely local rename stays local when the remote has no such file`() =
        runTest {
            val (hydration, provider, db) = freshEnv()
            seedLocalGhostRow(db, "/pending.deb")
            provider.ghostAt = null // not on the cloud → getMetadata throws not-found

            val result = hydration.rename("/pending.deb", "/pending-renamed.deb")

            assertEquals(RenameResult.Ok, result)
            assertNull(provider.lastMoveFrom, "a genuinely-never-uploaded file must NOT trigger a cloud move")
            assertNull(db.getEntry("/pending.deb"), "old path repathed")
            val moved = db.getEntry("/pending-renamed.deb")
            assertNotNull(moved, "row repathed locally")
            assertNull(moved.remoteId, "row stays local: (no remote to adopt)")
        }

    // Invariant 4: a TRANSIENT probe failure must fail the op, never fall to
    // local-only (which would skip a ghost's cloud move/delete and orphan it).
    @Test
    fun `transient probe failure fails the rename and leaves the row untouched`() =
        runTest {
            val (hydration, provider, db) = freshEnv()
            seedLocalGhostRow(db, "/maybe-ghost.deb")
            provider.transientError = ProviderException("Internxt 503 Service Unavailable") // not a not-found

            val result = hydration.rename("/maybe-ghost.deb", "/renamed.deb")

            assertTrue(result is RenameResult.Failed, "a transient probe failure must fail the rename, not go local-only")
            assertNull(provider.lastMoveFrom, "no cloud move attempted on a transient probe failure")
            assertNotNull(db.getEntry("/maybe-ghost.deb"), "row must stay put (not repathed) so the op can be retried")
            assertNull(db.getEntry("/renamed.deb"), "no row created at the new path")
        }

    @Test
    fun `transient probe failure fails the unlink and leaves the row untouched`() =
        runTest {
            val (hydration, provider, db) = freshEnv()
            seedLocalGhostRow(db, "/maybe-ghost.deb")
            provider.transientError = ProviderException("Internxt 503 Service Unavailable")

            val result = hydration.unlink("/maybe-ghost.deb")

            assertTrue(result is UnlinkResult.Failed, "a transient probe failure must fail the unlink, not hard-delete the row")
            assertNull(provider.lastDeletePath, "no cloud delete attempted on a transient probe failure")
            assertNotNull(db.getEntry("/maybe-ghost.deb"), "row must remain so the op can be retried")
        }
}
