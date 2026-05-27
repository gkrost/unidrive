package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * #110: legacy SyncEngine self-heals a 410-Gone expired delta cursor.
 *
 * A persisted OneDrive `@odata.deltaLink` can age out / the drive re-keys —
 * Graph returns 410 Gone on the delta endpoint.  The legacy engine must:
 *   (a) clear the persisted delta_cursor on a 410
 *   (b) re-run a FULL enumeration (cursor=null / incremental=false)
 *   (c) converge (the pass is complete, not stuck in an incomplete loop)
 *   (d) reap a path genuinely absent from the full enum while sparing present paths
 *
 * Negative invariant: if the recovery full-enum ITSELF throws, the engine
 * degrades safely (ProviderException propagates; caller treats as enumeration
 * failure with deletes suppressed) — no infinite re-recovery.
 */
class LegacyEngine410SelfHealTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-410-test")
        val dbPath = Files.createTempDirectory("ud-410-db").resolve("state.db")
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
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = hash,
        mimeType = null,
        deleted = deleted,
    )

    // ---- helpers ----

    /**
     * Run an initial full sync that seeds state.db with [items] and establishes
     * a persisted delta cursor.  Returns with the engine's state matching [items].
     */
    private suspend fun seedInitialSync(items: List<CloudItem>) {
        provider.deltaItems = items
        provider.deltaCursor = "cursor-after-initial"
        engine().syncOnce()
        // After the initial sync the cursor is in pending_cursor; promote it.
        // (SyncEngine.syncOnce promotes via promotePendingCursor internally at the
        // end of the pass if no actions remain or at the end of Pass 2.)
        val cursor = db.getSyncState("delta_cursor") ?: db.getSyncState("pending_cursor")
        assertNotNull(cursor, "initial sync must produce a delta_cursor")
    }

    // =========================================================================
    // (a) + (b) + (c): cursor is cleared and a full re-enum runs on 410
    // =========================================================================

    @Test
    fun `delta_cursor is cleared and full re-enumeration runs after a 410 on a resumed cursor`() =
        runTest {
            // Seed state with one file so there is a persisted cursor to resume.
            seedInitialSync(listOf(cloudItem("/keep.txt", size = 10)))

            // Verify a cursor is now persisted (pre-condition for the test to be meaningful).
            val cursorBefore = db.getSyncState("delta_cursor")?.ifBlank { null }
                ?: db.getSyncState("pending_cursor")?.ifBlank { null }
            assertNotNull(cursorBefore, "pre-condition: delta_cursor must be non-null before resumed pass")

            // Arm 410 on the NEXT resumed delta call, then return a normal full-enum
            // (the provider self-clears after the first throw so recovery call sees items).
            provider.deltaThrowExpiredOnResumedCursor = true
            val newItems = listOf(cloudItem("/keep.txt", size = 10), cloudItem("/new.txt", size = 20))
            provider.deltaItems = newItems
            provider.deltaCursor = "cursor-after-recovery"
            provider.files["/new.txt"] = ByteArray(20) { 0x42 }

            // The engine must not throw — it must recover internally.
            engine().syncOnce()

            // (a) delta_cursor must be cleared (written as "" then re-populated after recovery).
            // After a successful recovery pass the cursor is advanced to the recovery result.
            val cursorAfter = db.getSyncState("delta_cursor")?.ifBlank { null }
                ?: db.getSyncState("pending_cursor")?.ifBlank { null }
            assertNotNull(cursorAfter, "cursor must be populated after successful recovery full-enum")

            // The engine did NOT get stuck — it completed successfully (no exception thrown,
            // new file now visible in state.db).
            assertNotNull(db.getEntry("/new.txt"), "(c) /new.txt must be in state.db after recovery full-enum")
        }

    // =========================================================================
    // (d): present paths are spared; absent paths are reaped
    // =========================================================================

    @Test
    fun `present path is spared and absent path is reaped after 410 full re-enumeration`() =
        runTest {
            // Seed state.db directly (no local files) so the local scanner sees both as absent.
            // This avoids the "user deleted local copy" interpretation that the reconciler
            // would give to a previously-hydrated file that's no longer on disk.
            // Both rows are unhydrated (isHydrated=false) — the engine never downloaded them;
            // we're purely testing the remote-delete reaping path via detectMissingAfterFullSync.
            val seedInstant = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/present.txt",
                    remoteId = "id-present",
                    remoteHash = "hash-present",
                    remoteSize = 10L,
                    remoteModified = seedInstant,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = seedInstant,
                ),
            )
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/gone.txt",
                    remoteId = "id-gone",
                    remoteHash = "hash-gone",
                    remoteSize = 20L,
                    remoteModified = seedInstant,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = seedInstant,
                ),
            )
            // Seed a stale delta cursor so the resumed pass can get a 410.
            db.setSyncState("delta_cursor", "stale-cursor-for-reap-test")

            assertNotNull(db.getEntry("/present.txt"), "pre: /present.txt must be in state.db")
            assertNotNull(db.getEntry("/gone.txt"), "pre: /gone.txt must be in state.db")

            // Arm 410; recovery full-enum returns only /present.txt — /gone.txt is gone from cloud.
            provider.deltaThrowExpiredOnResumedCursor = true
            provider.deltaItems = listOf(cloudItem("/present.txt", size = 10))
            provider.deltaCursor = "cursor-recovery-2"
            provider.files["/present.txt"] = ByteArray(10) { 0x01 }

            engine().syncOnce()

            // (d) /gone.txt is absent from the recovery full-enum → detectMissingAfterFullSync
            // marks it deleted → reconciler sees remote=deleted + local=absent → RemoveEntry
            // (state.db row removed).  /present.txt is present in the full-enum → stays in
            // state.db (placeholder or DownloadContent action; either way the row survives).
            assertNotNull(db.getEntry("/present.txt"), "(d) /present.txt must survive the recovery pass")
            assertNull(db.getEntry("/gone.txt"), "(d) /gone.txt must be reaped — absent from the full re-enum")
        }

    // =========================================================================
    // Negative: 410 on the recovery pass itself degrades safely, no recursion
    // =========================================================================

    /**
     * A minimal CloudProvider whose delta() ALWAYS throws DeltaCursorExpiredException,
     * for both non-null and null cursors.  Used to verify the engine does not
     * attempt recursive recovery (the catch block must not call back into itself).
     */
    private class AlwaysExpiredDeltaProvider : CloudProvider {
        override val id = "always-expired-fake"
        override val displayName = "AlwaysExpiredFake"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}
        override suspend fun logout() {}
        override suspend fun listChildren(path: String) = emptyList<CloudItem>()
        override suspend fun getMetadata(path: String): CloudItem = error("unused in this test")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("unused in this test")

        override suspend fun delete(remotePath: String) {}
        override suspend fun createFolder(path: String): CloudItem = error("unused in this test")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("unused in this test")
        override suspend fun quota() = QuotaInfo(total = 0, used = 0, remaining = 0)

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            throw DeltaCursorExpiredException("410 always — both resumed and null cursors")
        }
    }

    @Test
    fun `410 on recovery pass itself degrades safely — no infinite recursion`() =
        runTest {
            val alwaysExpiredProvider = AlwaysExpiredDeltaProvider()
            val localDb = StateDatabase(
                Files.createTempDirectory("ud-410-neg-db").resolve("state.db"),
            )
            localDb.initialize()
            // Seed a cursor so the first delta() call is a "resumed" (non-null cursor) call.
            localDb.setSyncState("delta_cursor", "stale-cursor-for-neg-test")
            val localEngine =
                SyncEngine(
                    provider = alwaysExpiredProvider,
                    db = localDb,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )
            try {
                // The engine must either:
                //   (a) throw a ProviderException (the recovery 410 propagates), or
                //   (b) return normally with an incomplete enumeration (deletes suppressed).
                // Either is correct — what must NOT happen is infinite recursion /
                // StackOverflowError / a hanging test.
                localEngine.syncOnce()
            } catch (e: DeltaCursorExpiredException) {
                // Acceptable: typed expired signal propagated from recovery pass.
            } catch (e: ProviderException) {
                // Acceptable: generic provider failure from recovery pass.
            } finally {
                localDb.close()
            }
            // If we reach here without a stack overflow or test timeout, the
            // no-infinite-recursion invariant is satisfied.
        }
}
