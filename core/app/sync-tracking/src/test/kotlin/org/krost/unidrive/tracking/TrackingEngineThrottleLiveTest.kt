package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Throttling / 429-storm handling — structurally exercised through the engine.
 *
 * The gap this closes: real-account runs never tripped their provider's rate
 * limiter (the natural request rate of a single bulk enumeration stayed under
 * the limit), so the engine's interaction with a throttle storm was never
 * verified end-to-end.
 *
 * The engine has no 429-retry of its own — the provider's `HttpRetryBudget`
 * (honour-Retry-After, circuit-breaker, concurrency-halving) absorbs the storm
 * and is unit-tested in `:app:core` (`HttpRetryBudgetMatrixTest`). What the
 * ENGINE sees is the storm's downstream effect: a retry budget that exhausts
 * mid-walk yields a PARTIAL inventory (`DeltaPage.complete = false`). So the
 * engine-level invariant to pin is: **while the storm degrades enumeration to
 * partial, the engine suppresses deletes (a throttled partial view must never
 * be read as remote-gone), and once the storm clears and a complete inventory
 * arrives, the engine converges to the correct plan.**
 *
 * These run on the ROUTINE tier: deterministic, fake-driven, no network
 * or credentials, milliseconds to run.
 */
class TrackingEngineThrottleLiveTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("ts-throttle")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    /**
     * INVARIANT (safety): while a throttle storm forces partial
     * enumerations, NO tracked path is deleted even though local copies are
     * gone and a permissive BatchGuard would otherwise wave the deletes
     * through. A throttled partial view is not authority to delete.
     *
     * Regression mode if removed/loosened: a 429 storm on a large account
     * could degrade enumeration to partial and the engine would read the
     * unseen tracked paths as remote-gone → mass local delete. This is the
     * production-scale variant of the delete-cascade the engine exists to
     * prevent.
     */
    @Test
    fun `a throttle storm degrades enumeration to partial and suppresses all deletes`() {
        provider.files["/a.txt"] = "a".toByteArray()
        provider.files["/b.txt"] = "b".toByteArray()

        // Pass 1: clean — both files TrackedSynced + downloaded.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }

        // Pass 2: local copies vanish AND the next enumeration is throttled to
        // a partial inventory (retry budget exhausted under a 429 storm).
        Files.delete(syncRoot.resolve("a.txt"))
        Files.delete(syncRoot.resolve("b.txt"))
        provider.deltaCursors.clear()
        provider.throttleIncompletePasses = 1
        provider.retryAfterMs = 5_000L

        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(provider, tracking2, syncRoot, batchGuard = permissive, dryRun = true).syncOnce()

            assertFalse(
                report.remoteEnumerationComplete,
                "a throttled partial enumeration must mark the pass incomplete",
            )
            assertEquals(
                0,
                report.effectivePlan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                },
                "a throttled partial view must suppress every delete from the effective plan",
            )
            // The server-hinted Retry-After was observed, not discarded.
            assertEquals(
                listOf(5_000L),
                provider.observedRetryAfterMs,
                "the throttle's Retry-After hint must be recorded for the throttled pass",
            )
            assertEquals(
                2,
                tracking2.paths().size,
                "tracked rows must survive a throttled pass; got ${tracking2.paths()}",
            )
        } finally {
            tracking2.close()
        }
    }

    /**
     * INVARIANT (convergence): once the storm clears and a COMPLETE
     * inventory arrives, the engine converges to the correct plan — a genuine
     * remote deletion that happened is finally reaped, and surviving paths are
     * kept. Throttling delays convergence; it does not corrupt it.
     *
     * Regression mode if removed/loosened: the engine could stay permanently
     * stuck in delete-suppression after a storm (never reaping genuine
     * deletes), or — worse — act on a stale partial view. This test proves the
     * storm is a transient brake, not a latch.
     */
    @Test
    fun `the engine converges to the correct plan once the storm clears`() {
        provider.files["/keep.txt"] = "keep".toByteArray()
        provider.files["/gone.txt"] = "gone".toByteArray()

        // Pass 1: clean — both TrackedSynced + downloaded, full cursor saved.
        provider.nextCursor = "cursor-after-full"
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }

        // `/gone.txt` is genuinely removed remotely. The next pass is throttled
        // to partial (storm), then the pass after clears. Use a FULL enumeration
        // each pass (clear the cursor) so the remote's absence of `/gone.txt` is
        // authoritative once a complete pass lands.
        provider.files.remove("/gone.txt")
        provider.throttleIncompletePasses = 1

        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

        // Pass 2 (throttled → partial): deletes suppressed, nothing reaped yet.
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tracking2.saveDeltaCursor(null) // force a full enumeration this pass
            val throttled =
                TrackingEngine(provider, tracking2, syncRoot, batchGuard = permissive).syncOnce()
            assertFalse(throttled.remoteEnumerationComplete, "pass 2 is throttled → incomplete")
            assertTrue(
                provider.deletedPaths.isEmpty(),
                "no remote delete is issued during the throttled pass",
            )
            assertEquals(
                2,
                tracking2.paths().size,
                "both tracked rows survive the throttled pass",
            )
        } finally {
            tracking2.close()
        }

        // Pass 3 (storm cleared → complete): the genuine remote deletion of
        // `/gone.txt` is finally reaped; `/keep.txt` survives.
        val tracking3 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            tracking3.saveDeltaCursor(null) // full enumeration: remote absence is authoritative
            val converged =
                TrackingEngine(provider, tracking3, syncRoot, batchGuard = permissive).syncOnce()

            assertTrue(converged.remoteEnumerationComplete, "post-storm pass must complete")
            val remoteDeletes =
                converged.effectivePlan
                    .filterIsInstance<ReconcileAction.PropagateRemoteDelete>()
                    .map { it.path }
            assertEquals(
                listOf("/gone.txt"),
                remoteDeletes,
                "once the storm clears, the genuine remote deletion must be reaped; got $remoteDeletes",
            )
            assertFalse(
                Files.exists(syncRoot.resolve("gone.txt")),
                "the reaped path's local copy must be removed",
            )
            assertTrue(
                Files.exists(syncRoot.resolve("keep.txt")),
                "the surviving path's local copy must be kept",
            )
            assertEquals(
                1,
                tracking3.paths().size,
                "only the surviving tracked path remains after convergence",
            )
        } finally {
            tracking3.close()
        }
    }
}
