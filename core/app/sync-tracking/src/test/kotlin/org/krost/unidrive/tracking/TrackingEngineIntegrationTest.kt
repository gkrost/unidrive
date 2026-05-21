package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
