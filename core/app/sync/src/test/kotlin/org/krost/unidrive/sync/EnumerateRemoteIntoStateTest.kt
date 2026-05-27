package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
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

class EnumerateRemoteIntoStateTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: EnumerateFakeProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-enum-root")
        dbDir = Files.createTempDirectory("ud-enum-db")
        cacheRoot = Files.createTempDirectory("ud-enum-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = EnumerateFakeProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
                cacheKey = "enum-test",
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enumerate upserts remote entries into state_db without scanning sync_root or planning deletes`() =
        runTest {
            provider.putRemote("/a.txt", "AAA")
            provider.putRemote("/dir", isFolder = true)
            provider.putRemote("/dir/b.txt", "BBB")

            val result = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok, "enumerate must succeed against an empty sync_root (no guard)")
            assertEquals(
                setOf("/a.txt", "/dir"),
                db.listDirectChildren("").map { it.path }.toSet(),
            )
            assertNotNull(db.getEntry("/dir/b.txt"))
            assertEquals(0, provider.deletedPaths.size, "enumerate must never call provider.delete")
            assertEquals(0, provider.uploadedPaths.size, "enumerate must never upload")
        }

    @Test
    fun `incomplete enumeration does not mark omitted rows deleted`() =
        runTest {
            provider.putRemote("/keep.txt", "K")
            engine.enumerateRemoteIntoState(reset = false)
            assertNotNull(db.getEntry("/keep.txt"))

            // Next delta is incomplete AND reports /keep.txt as a tombstone. The
            // §3.1 invariant: reaping is suppressed because the page is incomplete,
            // even though a tombstone is present.
            provider.markNextDeltaIncomplete(tombstones = listOf("/keep.txt"))
            val r = engine.enumerateRemoteIntoState(reset = false)

            assertFalse(r.complete)
            assertNotNull(db.getEntry("/keep.txt"), "tombstone on an INCOMPLETE delta must NOT be reaped")
            assertEquals(0, r.reaped)
        }

    @Test
    fun `complete enumeration reaps a remotely-deleted path`() =
        runTest {
            provider.putRemote("/gone.txt", "G")
            provider.putRemote("/stay.txt", "S")
            engine.enumerateRemoteIntoState(reset = false)

            provider.removeRemote("/gone.txt")
            val r = engine.enumerateRemoteIntoState(reset = true)

            assertTrue(r.complete)
            assertNull(db.getEntry("/gone.txt"), "complete enum must reap /gone.txt (alive view excludes DELETED)")
            assertNotNull(db.getEntry("/stay.txt"))
            assertEquals(1, r.reaped)
            assertEquals(0, provider.deletedPaths.size, "reaping is a state.db flip, NOT a provider.delete")
        }

    @Test
    fun `reaping a remotely-deleted hydrated path evicts its cache file`() =
        runTest {
            provider.putRemote("/big.bin", "X".repeat(10))
            engine.enumerateRemoteIntoState(reset = false)
            val cache =
                engine.resolveCachePath("/big.bin").also {
                    Files.createDirectories(it.parent)
                    Files.writeString(it, "X".repeat(10))
                }
            assertTrue(Files.exists(cache))

            provider.removeRemote("/big.bin")
            engine.enumerateRemoteIntoState(reset = true)

            assertFalse(Files.exists(cache), "cache file must be evicted when the remote path is reaped")
        }

    @Test
    fun `enumerate resumes from the persisted cursor unless reset`() =
        runTest {
            provider.putRemote("/a.txt", "A")

            engine.enumerateRemoteIntoState(reset = false)
            // First gather ran against a null (full) cursor and persisted cursor-1.
            assertEquals(null, provider.deltaCursorsSeen[0])
            val persisted = db.getSyncState("delta_cursor")
            assertEquals("cursor-1", persisted, "first enumerate must promote the gathered cursor")

            engine.enumerateRemoteIntoState(reset = false)
            // Second gather must resume from the cursor the first one ended on.
            assertEquals("cursor-1", provider.deltaCursorsSeen[1])

            engine.enumerateRemoteIntoState(reset = true)
            // reset must re-enumerate from a null cursor.
            assertEquals(null, provider.deltaCursorsSeen[2])
        }

    // Invariant (Codex #91 P1): enumerateRemoteIntoState is single-flight across ALL
    // callers (poller, sync.enumerate verb, mount-routed refresh.run). A pass that
    // starts while another is in flight is a no-op (skipped=true) — it must NOT run a
    // concurrent pass that could race delta_cursor promotion / corroboration state.
    @Test
    fun `concurrent enumerate is single-flighted — the loser is a skipped no-op`() =
        runTest {
            provider.putRemote("/a.txt", "A")
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.deltaEntered = entered
            provider.deltaGate = gate

            // First pass acquires the guard and parks inside delta() on the gate.
            val first = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()

            // Second pass, started while the first holds the guard, must CAS-fail and
            // return immediately — it never reaches the (gated) provider.
            val second = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(second.ok)
            assertTrue(second.skipped, "a concurrent enumerate must be a single-flight no-op")
            assertEquals(1, provider.deltaCursorsSeen.size, "the skipped pass must not call the provider")

            gate.complete(Unit)
            val firstResult = first.await()
            assertTrue(firstResult.ok)
            assertFalse(firstResult.skipped, "the pass that won the guard actually ran")
            assertEquals(1, provider.deltaCursorsSeen.size, "only the winning pass called the provider")
        }

    // Invariant (Codex #91 P2): an INCOMPLETE enumeration resets the bulk-disappearance
    // deferred set, so a later COMPLETE pass must re-defer rather than reap against stale
    // corroboration state. Bulk reaping requires confirmation by CONSECUTIVE complete
    // enumerations; an incomplete pass breaks the chain.
    @Test
    fun `incomplete pass resets bulk-deferred state so the next complete pass re-defers not reaps`() =
        runTest {
            // > BULK_REAP_ABSOLUTE (50) so removing all trips the bulk-disappearance guard.
            repeat(60) { provider.putRemote("/f$it.txt", "x") }
            engine.enumerateRemoteIntoState(reset = true)

            // Remove all 60: a complete pass sees 60 missing > threshold 50 → BULK → defers
            // all (deferredMissing was empty), reaps nothing, carries the 60 forward.
            repeat(60) { provider.removeRemote("/f$it.txt") }
            val firstComplete = engine.enumerateRemoteIntoState(reset = true)
            assertEquals(0, firstComplete.reaped, "first bulk pass defers, reaps nothing")

            // An INCOMPLETE pass in between must reset the deferred set.
            provider.markNextDeltaIncomplete()
            val incomplete = engine.enumerateRemoteIntoState(reset = false)
            assertFalse(incomplete.complete)
            assertEquals(0, incomplete.reaped)

            // Next COMPLETE pass: with the reset, the 60 still-missing paths are re-deferred,
            // NOT reaped. Without the fix the stale deferred set would reap all 60 here.
            val secondComplete = engine.enumerateRemoteIntoState(reset = true)
            assertEquals(0, secondComplete.reaped, "incomplete pass broke corroboration; must re-defer, not reap")
            assertNotNull(db.getEntry("/f0.txt"), "deferred paths must survive (not reaped)")
        }

    // ── view-invalidation sink tests ──────────────────────────────────────────

    @Test
    fun `enumerate_emits_view_invalidation_with_changed_paths`() =
        runTest {
            // Wire a sink that records calls.
            val sinkInvocations = mutableListOf<Set<String>>()
            val engineWithSink =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    cacheKey = "enum-test",
                    viewInvalidationSink = { paths -> sinkInvocations.add(paths) },
                )

            // Scenario: two remote files added (upserted), then one is deleted (reaped).
            provider.putRemote("/add1.txt", "A")
            provider.putRemote("/add2.txt", "B")
            engineWithSink.enumerateRemoteIntoState(reset = false)

            // First pass: 2 upserts, 0 reaps — sink called once with both paths.
            assertEquals(1, sinkInvocations.size, "sink must be called exactly once when something changes")
            assertTrue("/add1.txt" in sinkInvocations[0], "upserted path must appear in sink args")
            assertTrue("/add2.txt" in sinkInvocations[0], "upserted path must appear in sink args")

            // Second pass: remove one path so it gets reaped on a complete enumeration.
            sinkInvocations.clear()
            provider.removeRemote("/add1.txt")
            engineWithSink.enumerateRemoteIntoState(reset = true)

            // add2 was re-upserted (full re-enum), add1 was reaped.
            assertEquals(1, sinkInvocations.size, "sink must fire once on second changed pass")
            assertTrue("/add1.txt" in sinkInvocations[0], "reaped path must appear in sink args")
        }

    @Test
    fun `enumerate_with_no_changes_emits_no_invalidation`() =
        runTest {
            val sinkInvocations = mutableListOf<Set<String>>()
            val engineWithSink =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    cacheKey = "enum-test",
                    viewInvalidationSink = { paths -> sinkInvocations.add(paths) },
                )

            // Empty remote — no upserts, no reaps.
            val result = engineWithSink.enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.upserted)
            assertEquals(0, result.reaped)
            assertEquals(0, sinkInvocations.size, "sink must NOT be called when nothing changed")
        }

    @Test
    fun `enumerate_emits_view_invalidation_with_aliased_view_path_not_canonical_remote_key`() =
        runTest {
            Files.createDirectories(syncRoot.resolve("Dokumente"))

            val sinkInvocations = mutableListOf<Set<String>>()
            val engineWithSink =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    cacheKey = "enum-test",
                    viewInvalidationSink = { paths -> sinkInvocations.add(paths) },
                    xdgUserDirsOverridesForTest = emptyMap(),
                )

            provider.putRemote("/Documents", isFolder = true)
            provider.putRemote("/Documents/a.txt", "HELLO")
            engineWithSink.enumerateRemoteIntoState(reset = false)

            assertEquals(1, sinkInvocations.size, "sink must fire on first enumerate with new paths")
            val emittedPaths = sinkInvocations[0]

            assertTrue(
                "/Dokumente/a.txt" in emittedPaths,
                "sink must receive the VIEW path /Dokumente/a.txt; got $emittedPaths",
            )
            assertTrue(
                "/Dokumente" in emittedPaths,
                "sink must receive the VIEW path /Dokumente for the folder; got $emittedPaths",
            )
            assertFalse(
                "/Documents/a.txt" in emittedPaths,
                "sink must NOT receive the canonical /Documents/a.txt; got $emittedPaths",
            )
            assertFalse(
                "/Documents" in emittedPaths,
                "sink must NOT receive the canonical /Documents; got $emittedPaths",
            )
        }
    // ── Top-level fake provider — follows the ThrottledProviderTest /
    // CloudRelocatorTest per-test fake convention. Adds the hooks the
    // enumerate path needs: a settable remote, recorded cursors, and an
    // on-demand incomplete delta. Cursor model: each delta() advances the
    // returned cursor monotonically; the engine resumes by passing the
    // previously-returned cursor back in.
    class EnumerateFakeProvider : CloudProvider {
        override val id = "enum-fake"
        override val displayName = "Enumerate Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> =
            setOf(org.krost.unidrive.Capability.Delta)

        private val remote = linkedMapOf<String, CloudItem>()

        val deletedPaths = mutableListOf<String>()
        val uploadedPaths = mutableListOf<String>()

        // Cursors the engine passed into delta() across calls (null = full enum).
        val deltaCursorsSeen = mutableListOf<String?>()
        private var nextCursorSeq = 0
        private var nextDeltaIncomplete = false
        private var nextDeltaTombstones = listOf<String>()

        // Single-flight test hooks: delta() completes [deltaEntered] (signalling the
        // pass has acquired the in-flight guard and reached the provider) then awaits
        // [deltaGate], letting a test hold one enumerate pass mid-flight while it starts
        // a second one. Both default to null (no gating).
        var deltaEntered: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var deltaGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun putRemote(path: String, content: String = "", isFolder: Boolean = false) {
            remote[path] =
                CloudItem(
                    id = "id-$path",
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = if (isFolder) 0L else content.toByteArray().size.toLong(),
                    isFolder = isFolder,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = if (isFolder) null else "h-$content",
                    mimeType = null,
                )
        }

        fun removeRemote(path: String) {
            remote.remove(path)
        }

        /**
         * The NEXT delta() call returns complete=false and carries the given paths
         * as deleted=true tombstones (single-use). Models a provider that reports
         * some deletions then 503's a subtree — the enumerate path must suppress
         * reaping on the incomplete page regardless of the carried tombstones.
         */
        fun markNextDeltaIncomplete(tombstones: List<String> = emptyList()) {
            nextDeltaIncomplete = true
            nextDeltaTombstones = tombstones
        }

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = remote.getValue(path)

        private fun tombstoneItem(path: String) =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                deleted = true,
            )

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCursorsSeen.add(cursor)
            deltaEntered?.complete(Unit)
            deltaGate?.await()
            val complete = !nextDeltaIncomplete
            // On an incomplete delta, return only the explicit tombstones (a throttled
            // subtree skip that still reported some deletions) — the engine must NOT
            // reap them while the page is incomplete.
            val items =
                if (nextDeltaIncomplete) {
                    nextDeltaTombstones.map { tombstoneItem(it) }
                } else {
                    remote.values.toList()
                }
            nextDeltaIncomplete = false
            nextDeltaTombstones = emptyList()
            nextCursorSeq++
            return DeltaPage(
                items = items,
                cursor = "cursor-$nextCursorSeq",
                hasMore = false,
                complete = complete,
            )
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("enumerate path must not download")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadedPaths.add(remotePath)
            error("enumerate path must not upload")
        }

        override suspend fun delete(remotePath: String) {
            deletedPaths.add(remotePath)
        }

        override suspend fun createFolder(path: String) = error("enumerate path must not createFolder")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = error("enumerate path must not move")

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)
    }
}
