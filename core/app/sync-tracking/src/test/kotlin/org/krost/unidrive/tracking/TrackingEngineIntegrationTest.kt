package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for the tracking-set engine.
 *
 * The headline scenario is the `.safe/` regression: an interrupted
 * first-sync used to leave 28 phantom folder rows in the legacy
 * `state.db`, which the existing engine read as "user-delete intent"
 * and turned into 28 `del-remote` actions on the next pass. The
 * tracking-set engine MUST make that class of bug structurally
 * impossible because untracked paths are invisible to deletion logic
 * AND because pending-state crash-recovery re-derives from live
 * observations rather than from the pending intent.
 *
 * The lemma this test proves: a tracking-set sync pass NEVER emits a
 * [ReconcileAction.PropagateRemoteDelete] for an untracked path. The
 * crash-resumption test is the specific instance — but the same
 * invariant holds across every shape of "tracking_db state
 * disagrees with reality" we can throw at it.
 */
class TrackingEngineIntegrationTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-it")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun engine(
        dryRun: Boolean = false,
        guard: BatchGuard = BatchGuard(),
        tracking: TrackingSet,
    ) = TrackingEngine(provider, tracking, syncRoot, batchGuard = guard, dryRun = dryRun)

    /**
     * The regression test. Reproduces the .safe/ shape:
     *
     *  1. Empty tracking.db, fresh remote with 30 files under /.safe/.
     *  2. Pass 1 (the "first sync") starts the download loop, but we
     *     interrupt after only 5 files have been written + 5 tracking
     *     rows persisted as PendingDownload (the post-crash state of
     *     the original incident — except those phantom rows lived in
     *     state.db).
     *  3. We "restart" the engine on a fresh in-memory wrapper around
     *     the same on-disk tracking.db, run a normal pass, and assert
     *     that ZERO PropagateRemoteDelete actions were emitted.
     *
     * The bug class proved impossible: at no point in the recovery
     * pass does the reconciler see (track.exists, local.absent,
     * remote.present) and decide to delete the remote. The Pending
     * row's intent is "we wanted to download this"; live observations
     * say "local absent, remote present"; case analysis emits
     * DownloadRemote, not PropagateRemoteDelete.
     */
    @Test
    fun `safe-star regression — crash mid first-sync does not delete remote on resume`() {
        // Seed the remote with 30 files under /.safe/ (the original incident shape).
        for (i in 0 until 30) {
            provider.files["/.safe/file-$i.bin"] = "payload-$i".toByteArray()
        }

        // Simulate "interrupted first sync" by hand: pre-persist 5 PendingDownload
        // rows with NO local copy on disk. That's the post-crash state of the
        // original incident — pending rows in the DB, no bytes locally. We do this
        // by hand rather than by killing a thread because the test's job is to
        // prove the post-crash recovery branch is safe.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            for (i in 0 until 5) {
                tracking.upsert(
                    TrackingRecord(
                        path = "/.safe/file-$i.bin",
                        providerId = "fake",
                        remoteFileId = null,
                        state = TrackState.PendingDownload,
                        localHash = null,
                        localSize = null,
                        remoteEtag = null,
                        remoteSize = null,
                        lastSynced = Instant.now(),
                    ),
                )
            }
        } finally {
            tracking.close()
        }

        // Restart the engine — fresh handle, same on-disk DB.
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking2, dryRun = true).syncOnce()

            // INVARIANT 1 — the headline. ZERO PropagateRemoteDelete actions.
            // Before the tracking-set engine, an analogous state in the legacy
            // state.db produced 28 del-remote actions.
            val deletes = report.plan.filterIsInstance<ReconcileAction.PropagateRemoteDelete>()
            assertEquals(
                0,
                deletes.size,
                "post-crash recovery emitted PropagateRemoteDelete actions: $deletes",
            )
            // Also no local-delete propagation (symmetric).
            val localDeletes = report.plan.filterIsInstance<ReconcileAction.PropagateLocalDelete>()
            assertEquals(0, localDeletes.size, "post-crash recovery emitted PropagateLocalDelete actions: $localDeletes")

            // INVARIANT 2 — the recovery action stream is "download everything
            // we don't have locally yet". 30 remote files, 0 local, every one
            // should appear as DownloadRemote.
            val downloads = report.plan.filterIsInstance<ReconcileAction.DownloadRemote>().map { it.path }.toSet()
            val expected = (0 until 30).map { "/.safe/file-$it.bin" }.toSet()
            assertEquals(expected, downloads, "post-crash recovery did not plan a download for every remote file")
        } finally {
            tracking2.close()
        }
    }

    /**
     * The lemma in pure form. For ANY untracked path the engine sees
     * (local-absent or local-present, remote-absent or remote-present),
     * the reconciler MUST NOT emit a delete on either side.
     *
     * This is the structural-safety property the .safe/ regression
     * leans on. Tested directly here against the pure reconciler so a
     * future change to TrackingEngine (e.g. batching, parallelism)
     * can't silently break the invariant.
     */
    @Test
    fun `lemma — reconciler never emits a delete for an untracked path`() {
        val rec = TrackingReconciler()
        val none = LocalObservation(false, null, null, null, null)
        val somelocal = LocalObservation(true, "h1", 10, Instant.now(), null)
        val noremote = RemoteObservation(false, null, null, null, null, null)
        val someremote = RemoteObservation(true, "id", "h1", 10, "h1", Instant.now())
        val someremoteOtherHash = RemoteObservation(true, "id", "h2", 10, "h2", Instant.now())

        // 4 untracked × 4 observation tuples; none should produce a delete.
        for ((label, local, remote) in
            listOf(
                Triple("both-absent", none, noremote),
                Triple("pure-local", somelocal, noremote),
                Triple("pure-remote", none, someremote),
                Triple("both-present-match", somelocal, someremote),
                Triple("both-present-mismatch", somelocal, someremoteOtherHash),
            )) {
            val action = rec.reconcile("/$label", local, remote, track = null)
            assertFalse(
                action is ReconcileAction.PropagateLocalDelete ||
                    action is ReconcileAction.PropagateRemoteDelete,
                "untracked tuple '$label' emitted a delete action: $action",
            )
        }
    }

    /**
     * Sanity: a healthy round-trip works. Local + remote empty → seed
     * remote → sync downloads → second pass is a no-op (all adopted).
     */
    @Test
    fun `happy path — first sync downloads, second is a no-op`() {
        provider.files["/doc.txt"] = "hello world".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val firstReport = engine(tracking = tracking).syncOnce()
            assertEquals(1, firstReport.plan.size)
            assertTrue(firstReport.plan.single() is ReconcileAction.DownloadRemote)
            assertTrue(Files.exists(syncRoot.resolve("doc.txt")))

            val secondReport = engine(tracking = tracking).syncOnce()
            assertEquals(0, secondReport.plan.size, "second pass should be no-op, was ${secondReport.plan}")
            assertEquals(1, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }
    }

    /**
     * Two-way: a file created locally that the remote doesn't have is
     * uploaded, becomes tracked, and the next pass is a no-op. This is the
     * first-upload path — without it, `ts sync` can download/delete/modify but
     * cannot push a user's new file to the cloud.
     */
    @Test
    fun `first-upload — a new local file is uploaded and then a no-op`() {
        Files.createDirectories(syncRoot)
        Files.write(syncRoot.resolve("new.txt"), "fresh".toByteArray())
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val first = engine(tracking = tracking).syncOnce()
            assertEquals(1, first.plan.size, "expected one upload action, was ${first.plan}")
            assertTrue(first.plan.single() is ReconcileAction.UploadLocal, "expected UploadLocal, was ${first.plan}")
            assertTrue(provider.files.containsKey("/new.txt"), "the new file must be uploaded to the remote")
            assertEquals("fresh", String(provider.files["/new.txt"]!!))
            assertEquals(1, tracking.countsByState()[TrackState.TrackedSynced])

            val second = engine(tracking = tracking).syncOnce()
            assertEquals(0, second.plan.size, "second pass should be a no-op, was ${second.plan}")
        } finally {
            tracking.close()
        }
    }

    /**
     * Sanity: a tracked path that disappears locally DOES produce a
     * delete intent — the engine is not paralysed, only protected
     * against untracked-path deletion.
     */
    @Test
    fun `tracked local-gone produces propagate-local-delete (with permissive guard)`() {
        provider.files["/doc.txt"] = "hello".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("doc.txt")))
            Files.delete(syncRoot.resolve("doc.txt"))

            val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
            val report = engine(tracking = tracking, guard = permissive).syncOnce()
            assertEquals(1, report.plan.size)
            assertTrue(report.plan.single() is ReconcileAction.PropagateLocalDelete)
            assertTrue(provider.deletedPaths.contains("/doc.txt"))
        } finally {
            tracking.close()
        }
    }

    /**
     * BatchGuard trips when too much would be deleted in one pass — and
     * uploads / non-delete actions still proceed.
     */
    @Test
    fun `batch guard drops deletes but lets uploads through`() {
        // Seed 10 remote files, sync them all, then nuke local.
        for (i in 0 until 10) {
            provider.files["/file-$i.bin"] = "payload-$i".toByteArray()
        }
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            for (i in 0 until 10) Files.delete(syncRoot.resolve("file-$i.bin"))

            val tight = BatchGuard(maxDeleteRatio = 0.1, maxDeleteAbsolute = 2)
            val report = engine(tracking = tracking, guard = tight, dryRun = true).syncOnce()
            assertEquals(10, report.plan.count { it is ReconcileAction.PropagateLocalDelete })
            assertEquals(
                0,
                report.effectivePlan.count { it is ReconcileAction.PropagateLocalDelete },
                "BatchGuard should have dropped all 10 deletes",
            )
            assertTrue(report.guardVerdict is BatchGuard.Verdict.Deny)
        } finally {
            tracking.close()
        }
    }

    /**
     * Codex P1: when remote enumeration is incomplete (transient API
     * failure mid-walk, or a DeltaPage reporting complete=false), the
     * engine MUST suppress delete actions for tracked paths it didn't
     * see — otherwise those paths look remote-absent to the reconciler
     * and would emit PropagateRemoteDelete that pass through the
     * BatchGuard whenever delete counts stay under the ratio.
     */
    @Test
    fun `incomplete remote enumeration suppresses all delete actions`() {
        // Seed two remote files, sync them to TrackedSynced, then nuke local
        // copies AND tell the fake provider its next delta is incomplete.
        provider.files["/a.txt"] = "a".toByteArray()
        provider.files["/b.txt"] = "b".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])

            // Local copies vanish AND the next enumeration reports incomplete.
            // Without the fix, the engine would see remote items present + local
            // gone → 2 × PropagateLocalDelete, both passing BatchGuard at the
            // permissive setting.
            Files.delete(syncRoot.resolve("a.txt"))
            Files.delete(syncRoot.resolve("b.txt"))
            provider.deltaComplete = false

            val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
            val report = engine(tracking = tracking, guard = permissive, dryRun = true).syncOnce()

            // Plan still contains the would-be deletes; effectivePlan does not.
            assertEquals(
                2,
                report.plan.count { it is ReconcileAction.PropagateLocalDelete },
                "plan should still record the deletes the reconciler emitted",
            )
            assertEquals(
                0,
                report.effectivePlan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                },
                "incomplete-enumeration suppression must drop every delete from effectivePlan",
            )
            assertFalse(report.remoteEnumerationComplete)
        } finally {
            tracking.close()
        }
    }

    /**
     * Codex P2: when a tracked path's both sides have vanished, the
     * reconciler returns NoOp and the engine MUST remove the tracking
     * row (spec "both gone" cleanup). Otherwise stale rows accumulate,
     * inflate trackedTotal, weaken BatchGuard ratios, and pollute
     * `ts status`.
     */
    @Test
    fun `tracked row removed when both sides vanish`() {
        provider.files["/doc.txt"] = "hello".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(1, tracking.countsByState()[TrackState.TrackedSynced])

            // Disappear both sides — out-of-band local rm + remote rm.
            Files.delete(syncRoot.resolve("doc.txt"))
            provider.files.remove("/doc.txt")

            // The both-gone NoOp cleanup is only reachable on a FULL pass,
            // where the remote's absence of the path is authoritative. On an
            // incremental (cursor-resumed) pass a tracked path absent from the
            // delta is unchanged-present, not gone — a real removal would
            // arrive as a deleted-flagged delta item instead. Clear the cursor
            // so pass 2 full-enumerates (the post-reset / first-pass shape).
            tracking.saveDeltaCursor(null)

            val report = engine(tracking = tracking).syncOnce()
            assertTrue(
                report.cleanedUp.contains("/doc.txt"),
                "both-gone tracked path should be reported in cleanedUp; was ${report.cleanedUp}",
            )
            assertEquals(
                0,
                tracking.paths().size,
                "tracking set should be empty after both-gone cleanup; still had ${tracking.paths()}",
            )
            // No action should have been emitted (NoOp is filtered before plan).
            assertEquals(0, report.plan.size, "both-gone cleanup must not emit any action; plan=${report.plan}")
        } finally {
            tracking.close()
        }
    }

    /**
     * Cursor persistence: across two passes against the same on-disk
     * tracking.db, the SECOND pass's first delta() call must receive the
     * cursor the first (completed) pass ended on — NOT an empty/initial
     * cursor. Without persistence, every pass re-enumerates the full
     * remote inventory from scratch (catastrophic at the ~196k Internxt
     * scale).
     */
    @Test
    fun `completed pass persists delta cursor and next pass resumes from it`() {
        provider.files["/doc.txt"] = "hello world".toByteArray()
        provider.nextCursor = "cursor-after-pass-1"

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
        } finally {
            tracking.close()
        }
        assertEquals(
            listOf<String?>(null),
            provider.deltaCursors,
            "first pass should start from an empty cursor",
        )

        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking2).syncOnce()
        } finally {
            tracking2.close()
        }
        assertEquals(
            "cursor-after-pass-1",
            provider.deltaCursors[1],
            "second pass must resume from the cursor the first pass ended on, not an empty cursor",
        )
    }

    /**
     * Cursor-advance gate: when a pass returns complete == false, the
     * persisted cursor MUST NOT be advanced. The next pass must still
     * start from the prior cursor (or empty if none was ever stored), so
     * the enumeration re-runs safely and the delete-suppression backup
     * path's contract holds.
     */
    @Test
    fun `incomplete pass does not advance persisted delta cursor`() {
        provider.files["/doc.txt"] = "hello world".toByteArray()

        // Pass 1 completes and stores "cursor-1".
        provider.nextCursor = "cursor-1"
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals("cursor-1", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }

        // Pass 2 reports incomplete and would advance to "cursor-2" — must NOT persist it.
        provider.deltaComplete = false
        provider.nextCursor = "cursor-2"
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking2).syncOnce()
            assertEquals(
                "cursor-1",
                tracking2.loadDeltaCursor(),
                "incomplete pass must leave the prior persisted cursor in place",
            )
        } finally {
            tracking2.close()
        }

        // Pass 3 resumes from the prior cursor ("cursor-1"), not the discarded "cursor-2".
        provider.deltaComplete = true
        provider.deltaCursors.clear()
        val tracking3 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking3).syncOnce()
            assertEquals(
                "cursor-1",
                provider.deltaCursors.first(),
                "next pass after an incomplete pass must resume from the last completed cursor",
            )
        } finally {
            tracking3.close()
        }
    }

    /**
     * The cursor-resume deletion-cascade guard. This is the invariant the
     * cursor-persistence feature would otherwise have broken:
     *
     * A real provider returns a FULL inventory only on the first delta
     * (`cursor == null`). Once a pass persists a cursor, the NEXT pass
     * resumes from it and the provider returns an INCREMENTAL delta —
     * only items changed/deleted since that cursor. An unchanged tracked
     * path is simply OMITTED from the incremental page. The pre-fix engine
     * defaulted every omitted path to `RemoteObservation(exists = false)`,
     * which the reconciler reads as remote-gone → PropagateRemoteDelete.
     * That is the deletion cascade the engine exists to prevent.
     *
     * Here pass 1 full-enumerates N files (all become TrackedSynced, cursor
     * persisted). Pass 2 resumes from that cursor with an EMPTY incremental
     * delta (no changes, no deletes). Assert ZERO deletes: every omitted
     * tracked path must resolve to NoOp, not PropagateRemoteDelete, even
     * under a permissive BatchGuard that would otherwise wave the deletes
     * through.
     */
    @Test
    fun `incremental pass does not delete a tracked path merely absent from the delta`() {
        for (i in 0 until 5) {
            provider.files["/file-$i.bin"] = "payload-$i".toByteArray()
        }
        provider.nextCursor = "cursor-after-full"

        // Pass 1: full enumeration → 5 TrackedSynced rows, cursor persisted.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(5, tracking.countsByState()[TrackState.TrackedSynced])
            assertEquals("cursor-after-full", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }

        // Pass 2: resume from the cursor with an EMPTY incremental delta —
        // every tracked path is omitted (unchanged-present), nothing deleted.
        provider.incrementalAware = true // changes/deletes both empty
        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking2, guard = permissive, dryRun = true).syncOnce()

            // The incremental delta must have actually been exercised: pass 2's
            // first delta() call resumed from the persisted cursor, not null.
            assertEquals(
                "cursor-after-full",
                provider.deltaCursors.last(),
                "pass 2 must resume from the persisted cursor (so the delta is incremental)",
            )
            // The headline invariant: omitted tracked paths are NEVER deletes.
            assertEquals(
                0,
                report.plan.count {
                    it is ReconcileAction.PropagateRemoteDelete ||
                        it is ReconcileAction.PropagateLocalDelete
                },
                "tracked paths absent from an incremental delta must resolve to NoOp, not a delete; plan=${report.plan}",
            )
            // And they're genuinely NoOp: nothing surfaced in the plan at all.
            assertEquals(
                0,
                report.plan.size,
                "an empty incremental delta over unchanged tracked paths must emit no actions; plan=${report.plan}",
            )
            assertEquals(5, tracking2.paths().size, "no tracked rows should have been cleaned up")
        } finally {
            tracking2.close()
        }
    }

    /**
     * The other half of the incremental contract: a genuine remote removal
     * still propagates. On an incremental pass a tracked path is only
     * remote-gone when the delta carries an EXPLICIT deleted-flagged item
     * for it (OneDrive maps `@microsoft.graph.removed` / the `deleted`
     * facet onto `CloudItem.deleted`). Exactly the flagged path must yield
     * PropagateRemoteDelete; the others — omitted, hence unchanged-present
     * — must stay untouched.
     */
    @Test
    fun `incremental pass deletes a tracked path the delta explicitly marks deleted`() {
        for (i in 0 until 3) {
            provider.files["/file-$i.bin"] = "payload-$i".toByteArray()
        }
        provider.nextCursor = "cursor-after-full"

        // Pass 1: full enumeration → 3 TrackedSynced rows, cursor persisted.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(3, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }

        // Pass 2: incremental delta explicitly marks ONE tracked path deleted.
        provider.incrementalAware = true
        provider.incrementalDeletes += "/file-1.bin"
        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking2, guard = permissive, dryRun = true).syncOnce()

            val remoteDeletes =
                report.plan.filterIsInstance<ReconcileAction.PropagateRemoteDelete>().map { it.path }
            assertEquals(
                listOf("/file-1.bin"),
                remoteDeletes,
                "exactly the deleted-flagged path must propagate a remote delete; got $remoteDeletes",
            )
            assertEquals(
                0,
                report.plan.count { it is ReconcileAction.PropagateLocalDelete },
                "no local deletes should be emitted; plan=${report.plan}",
            )
            // The two omitted tracked paths are unchanged-present → no action.
            assertEquals(
                1,
                report.plan.size,
                "only the deleted-flagged path should produce an action; plan=${report.plan}",
            )
        } finally {
            tracking2.close()
        }
    }

    /**
     * UD-410: a 410 Gone on a resumed delta cursor (the cursor aged out /
     * the drive re-keyed) must self-heal into ONE full re-enumeration that
     * reconciles deletes correctly — not loop forever on an incomplete pass.
     *
     * This pins the whole recovery contract in a single scenario:
     *  - pass 1 full-enumerates two files, both → TrackedSynced, cursor saved;
     *  - pass 2 resumes from that cursor, the provider 410s, AND `/gone.txt`
     *    was genuinely removed remotely during the stale window;
     *  - the engine clears the cursor, re-runs from null as a FULL pass, and:
     *      • `/gone.txt` (tracked, absent from the FULL enum) IS reaped, and
     *      • `/keep.txt` (tracked, present in the FULL enum) is NOT deleted;
     *  - the pass is `complete = true` and `remoteEnumerationComplete = true`
     *    (so the engine converges instead of staying stuck), and the cursor is
     *    re-saved to the post-resync value.
     *
     * `incrementalAware = true` proves the recovery is genuinely FULL: an
     * incremental delta from the stale cursor would OMIT `/gone.txt` (absent ⇒
     * unchanged-present) and never reap it. Only a full re-enum from null sees
     * its true absence.
     */
    @Test
    fun `410 on resumed cursor self-heals into a full re-enumeration that reaps genuine deletes`() {
        provider.files["/keep.txt"] = "keep".toByteArray()
        provider.files["/gone.txt"] = "gone".toByteArray()
        provider.nextCursor = "cursor-after-full"

        // Pass 1: full enumeration → both files TrackedSynced + downloaded locally.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])
            assertEquals("cursor-after-full", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }

        // Pass 2: resume from the stored cursor; the provider 410s on it.
        // `/gone.txt` was removed remotely during the stale window; the post-410
        // FULL re-enum (cursor=null) returns the live `files` map without it.
        provider.expiredCursor = "cursor-after-full"
        provider.incrementalAware = true
        provider.files.remove("/gone.txt")
        provider.nextCursor = "cursor-after-resync"
        provider.deltaCursors.clear()

        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking2, guard = permissive).syncOnce()

            // The pass converged: full inventory seen, no infinite incomplete loop.
            assertTrue(report.remoteEnumerationComplete, "post-410 full re-enum must complete")

            // The recovery walked the cursor THEN re-walked from null.
            assertEquals(
                listOf<String?>("cursor-after-full", null),
                provider.deltaCursors,
                "engine must hit the stale cursor, then re-enumerate from null; was ${provider.deltaCursors}",
            )

            // The genuine remote deletion is reaped (tracked, absent from FULL enum).
            val remoteDeletes =
                report.effectivePlan.filterIsInstance<ReconcileAction.PropagateRemoteDelete>().map { it.path }
            assertEquals(
                listOf("/gone.txt"),
                remoteDeletes,
                "genuine remote deletion during the stale window must be reaped on the full re-enum; got $remoteDeletes",
            )
            assertTrue(provider.deletedPaths.isEmpty(), "no remote delete is issued — the file is already gone remotely")
            assertFalse(Files.exists(syncRoot.resolve("gone.txt")), "local copy of the reaped path must be removed")

            // The unchanged tracked path is NOT deleted, and survives locally.
            assertEquals(
                0,
                report.effectivePlan.count {
                    (it is ReconcileAction.PropagateRemoteDelete && it.path == "/keep.txt") ||
                        (it is ReconcileAction.PropagateLocalDelete && it.path == "/keep.txt")
                },
                "an unchanged tracked path present in the full enum must NOT be deleted",
            )
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")), "unchanged path's local copy must survive")

            // The cursor was cleared (during recovery) and then re-saved to the
            // value the post-resync full pass ended on.
            assertEquals(
                "cursor-after-resync",
                tracking2.loadDeltaCursor(),
                "the post-410 full pass must persist the fresh cursor it ended on",
            )
            assertEquals(1, tracking2.paths().size, "only the surviving tracked path remains")
        } finally {
            tracking2.close()
        }
    }

    /**
     * UD-410 failure path: if the post-410 FULL re-enumeration ITSELF fails
     * (any ProviderException), the engine must fall back to the existing
     * delete-suppression net — `complete = false`, every delete dropped from
     * the effective plan — and NOT throw out of the pass. The lemma's safety
     * net stays intact even when recovery can't reach the remote.
     */
    @Test
    fun `410 recovery whose full re-enum fails suppresses deletes and does not throw`() {
        provider.files["/a.txt"] = "a".toByteArray()
        provider.files["/b.txt"] = "b".toByteArray()
        provider.nextCursor = "cursor-after-full"

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            engine(tracking = tracking).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }

        // Pass 2: 410 on the stored cursor, AND the recovery full re-enum (null
        // cursor) also fails. Local copies vanish so, without suppression, both
        // tracked paths would look remote-absent → 2 deletes.
        Files.delete(syncRoot.resolve("a.txt"))
        Files.delete(syncRoot.resolve("b.txt"))
        provider.expiredCursor = "cursor-after-full"
        provider.failFullReenumeration = true

        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking2, guard = permissive, dryRun = true).syncOnce()

            assertFalse(report.remoteEnumerationComplete, "failed recovery must mark the pass incomplete")
            assertEquals(
                0,
                report.effectivePlan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                },
                "a failed 410 recovery must suppress every delete from the effective plan",
            )
            // Cursor was cleared by the recovery attempt and not re-saved (incomplete).
            assertEquals(null, tracking2.loadDeltaCursor(), "a failed recovery leaves the cursor cleared, not re-advanced")
        } finally {
            tracking2.close()
        }
    }

    /**
     * Spec Amendment 2: an untracked path that exists on BOTH sides with
     * matching content is adopted silently. With non-matching content
     * the engine emits a loud ReportCollision, never a download or
     * upload.
     */
    @Test
    fun `first-scan adoption on content match, collision on mismatch`() {
        // Match: local + remote both have "abc".
        Files.writeString(syncRoot.resolve("match.txt"), "abc")
        provider.files["/match.txt"] = "abc".toByteArray()
        // Mismatch: local "L", remote "R".
        Files.writeString(syncRoot.resolve("mismatch.txt"), "L")
        provider.files["/mismatch.txt"] = "R".toByteArray()

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking, dryRun = true).syncOnce()
            assertTrue(report.adopted.contains("/match.txt"))
            assertFalse(report.adopted.contains("/mismatch.txt"))
            val coll = report.collisions.map { it.path }
            assertTrue(coll.contains("/mismatch.txt"), "mismatch should be a collision, got $coll")
            assertEquals(
                0,
                report.plan.count {
                    it is ReconcileAction.PropagateLocalDelete || it is ReconcileAction.PropagateRemoteDelete
                },
                "untracked mismatch must NEVER produce a delete",
            )
        } finally {
            tracking.close()
        }
    }

    // ── Part 1: coexistence — legacy state.db is not read by the tracking engine ──

    /**
     * A profile directory that already contains a populated legacy `state.db`
     * must not affect the tracking-set engine in any way. The first `ts sync`
     * must produce adoption from LIVE observations only (local scan + remote
     * enumerate) — it must NOT import or read legacy rows.
     *
     * Concretely: we place a `state.db` next to `tracking.db` and pre-populate
     * it with a row for a path that does NOT exist on either the local scan
     * or the remote. If the engine read from `state.db` it would see a tracked
     * row and might emit a spurious delete. The correct behaviour is that the
     * engine never opens `state.db` and the spurious-path row remains invisible.
     */
    @Test
    fun `legacy state_db present does not affect tracking engine — first sync adopts via live observations only`() {
        // Seed the legacy state.db with a row for a path that does not exist
        // locally or remotely. If the tracking engine read it, it would see a
        // tracked entry with no live observations — a candidate for spurious cleanup
        // or delete propagation. It must not.
        val legacyDbPath = workDir.resolve("state.db")
        val legacyConn =
            java.sql.DriverManager.getConnection("jdbc:sqlite:$legacyDbPath").also { c ->
                c.createStatement().use { s ->
                    s.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS sync_entries (
                            path TEXT PRIMARY KEY,
                            local_hash TEXT,
                            remote_etag TEXT
                        )
                        """,
                    )
                    s.executeUpdate("INSERT INTO sync_entries VALUES ('/legacy-only.txt', 'lh', 're')")
                }
            }
        legacyConn.close()

        // Seed the real remote with a single file whose content matches locally.
        val content = "shared content".toByteArray()
        Files.write(syncRoot.resolve("shared.txt"), content)
        provider.files["/shared.txt"] = content

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = engine(tracking = tracking, dryRun = true).syncOnce()

            // INVARIANT 1: the engine must have adopted /shared.txt via live
            // content-hash match, NOT from any legacy import.
            assertTrue(
                report.adopted.contains("/shared.txt"),
                "first sync must adopt the live-matched path; adopted=${report.adopted}",
            )

            // INVARIANT 2: the legacy-only row must be invisible — no action,
            // no collision, no entry in tracking.db.
            val legacyInPlan = report.plan.any { it.path == "/legacy-only.txt" }
            assertFalse(
                legacyInPlan,
                "legacy-only path must be invisible to the tracking engine; plan=${report.plan}",
            )
            val legacyTracked = tracking.lookup("/legacy-only.txt")
            assertNull(
                legacyTracked,
                "legacy-only path must not appear in tracking.db after first sync",
            )

            // INVARIANT 3: zero deletes — neither side triggered a delete action.
            val deletes =
                report.plan.count {
                    it is ReconcileAction.PropagateLocalDelete || it is ReconcileAction.PropagateRemoteDelete
                }
            assertEquals(0, deletes, "first sync with legacy state.db present must emit zero deletes")
        } finally {
            tracking.close()
        }
    }

    // ── Part 2: --auto-match engine-level tests ──

    /**
     * DEFAULT behaviour preserved: when both hashes are null (Internxt first-scan)
     * and NO auto-match is configured, the engine must surface a ReportCollision.
     * This is the #104 invariant — verifying the default is unbroken by this change.
     */
    @Test
    fun `default_no_auto_match_null_hash_same_size_still_surfaces_collision`() {
        // Use a hashless fake provider (override to return null hash).
        val hashlessProvider = HashlessFakeProvider()
        val content = "some file".toByteArray()
        Files.write(syncRoot.resolve("file.txt"), content)
        hashlessProvider.files["/file.txt"] = content

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            // No autoMatch specified → defaults to OFF
            val report =
                TrackingEngine(
                    provider = hashlessProvider,
                    trackingSet = tracking,
                    syncRoot = syncRoot,
                    dryRun = true,
                ).syncOnce()

            val collisions = report.collisions.map { it.path }
            assertTrue(
                collisions.contains("/file.txt"),
                "null-hash same-size with no --auto-match must still produce a collision; collisions=$collisions",
            )
            assertFalse(
                report.adopted.contains("/file.txt"),
                "null-hash must NOT be adopted without --auto-match",
            )
        } finally {
            tracking.close()
        }
    }

    /**
     * --auto-match=size: when both hashes are null and sizes are EQUAL,
     * the engine must adopt the path (no collision).
     */
    @Test
    fun `auto_match_size_null_hash_same_size_adopts`() {
        val hashlessProvider = HashlessFakeProvider()
        val content = "some file".toByteArray()
        Files.write(syncRoot.resolve("file.txt"), content)
        hashlessProvider.files["/file.txt"] = content

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(
                    provider = hashlessProvider,
                    trackingSet = tracking,
                    syncRoot = syncRoot,
                    dryRun = true,
                    autoMatch = AutoMatchMode.SIZE,
                ).syncOnce()

            assertTrue(
                report.adopted.contains("/file.txt"),
                "null-hash same-size with auto-match=size must adopt; adopted=${report.adopted}",
            )
            assertEquals(
                0,
                report.collisions.size,
                "no collision expected when auto-match=size adopts; collisions=${report.collisions}",
            )
        } finally {
            tracking.close()
        }
    }

    /**
     * --auto-match=size with a SIZE MISMATCH must still surface a collision.
     * Size-match is required for the opt-in to fire.
     */
    @Test
    fun `auto_match_size_null_hash_size_mismatch_still_collides`() {
        val hashlessProvider = HashlessFakeProvider()
        // Local: 5 bytes; remote: 10 bytes — sizes differ.
        Files.write(syncRoot.resolve("file.txt"), "hello".toByteArray())
        hashlessProvider.files["/file.txt"] = "hello world".toByteArray()

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(
                    provider = hashlessProvider,
                    trackingSet = tracking,
                    syncRoot = syncRoot,
                    dryRun = true,
                    autoMatch = AutoMatchMode.SIZE,
                ).syncOnce()

            val collisions = report.collisions.map { it.path }
            assertTrue(
                collisions.contains("/file.txt"),
                "null-hash size-mismatch with auto-match=size must still collide; collisions=$collisions",
            )
            assertFalse(report.adopted.contains("/file.txt"), "size-mismatch must NOT adopt")
        } finally {
            tracking.close()
        }
    }

    /**
     * --auto-match=name: when both hashes are null and the path is the same,
     * the engine must adopt regardless of size.
     */
    @Test
    fun `auto_match_name_null_hash_same_path_adopts`() {
        val hashlessProvider = HashlessFakeProvider()
        // Local and remote have the same path but different sizes — name-only match.
        Files.write(syncRoot.resolve("file.txt"), "local content".toByteArray())
        hashlessProvider.files["/file.txt"] = "different remote content".toByteArray()

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(
                    provider = hashlessProvider,
                    trackingSet = tracking,
                    syncRoot = syncRoot,
                    dryRun = true,
                    autoMatch = AutoMatchMode.NAME,
                ).syncOnce()

            assertTrue(
                report.adopted.contains("/file.txt"),
                "null-hash with auto-match=name must adopt regardless of size; adopted=${report.adopted}",
            )
            assertEquals(
                0,
                report.collisions.size,
                "no collision expected when auto-match=name adopts; collisions=${report.collisions}",
            )
        } finally {
            tracking.close()
        }
    }
}
