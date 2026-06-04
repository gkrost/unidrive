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
 * #161: JWT / OAuth-refresh path — structurally exercised through the engine.
 *
 * The gap #161 names: a 62-minute real Internxt enumeration produced ZERO
 * token-refresh log lines, and the 23.55 s OneDrive pass started minutes
 * after a fresh `auth`, so neither real-account run actually spanned a token
 * refresh. The live tests passed without proving the refresh path works.
 *
 * The tracking engine has no token logic of its own — refresh is owned by the
 * provider's HTTP client, which recovers a 401 mid-enumeration via a
 * refresh-token round-trip and retries transparently (see
 * `RefreshableTokenLatch`, OneDrive `TokenManager`). So the engine-level
 * invariant to pin is: **when the provider's token expires mid-enumeration and
 * the provider refreshes transparently, the engine's `syncOnce()` completes as
 * if nothing happened — same plan, no error surfaced, no spurious delete.**
 * The complementary invariant: **when the refresh itself fails, the engine
 * does NOT propagate deletes** (an auth blip must never be read as
 * remote-gone).
 *
 * These run on the ROUTINE tier (#133): they use [FakeTrackingProvider]'s
 * refresh seam, are deterministic, need no network or credentials, and finish
 * in milliseconds — exactly the tests that belong on the per-PR loop the slow
 * real-account live tests were evicted from.
 */
class TrackingEngineRefreshLiveTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("ts-refresh")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider()
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    /**
     * INVARIANT (#161 happy path): a token that expires on the first
     * enumeration call is transparently refreshed, the pass completes, the
     * refresh fired exactly once, and the plan is identical to the no-expiry
     * case (every remote file → DownloadRemote, zero deletes).
     *
     * Regression mode if this test is removed/loosened: a stale-token failure
     * mid-enumeration would never be reproduced in CI; production could surface
     * silent enumeration stalls or spurious deletes after the JWT TTL elapses
     * on a long sync, exactly the #161 blind spot.
     */
    @Test
    fun `token expiry mid-enumeration is transparently refreshed and the pass converges`() {
        for (i in 0 until 5) {
            provider.files["/file-$i.bin"] = "payload-$i".toByteArray()
        }
        // The first delta() call sees an expired token; the provider refreshes
        // transparently (refreshOnTokenExpiry defaults to true).
        provider.tokenExpiresAtDeltaCall = 0

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report = TrackingEngine(provider, tracking, syncRoot, dryRun = true).syncOnce()

            assertEquals(1, provider.refreshCount, "exactly one transparent refresh must have fired")
            assertTrue(
                report.remoteEnumerationComplete,
                "a transparently-refreshed enumeration must still complete",
            )
            // The plan is exactly what a no-expiry run would produce: 5 downloads.
            val downloads =
                report.plan.filterIsInstance<ReconcileAction.DownloadRemote>().map { it.path }.toSet()
            assertEquals(
                (0 until 5).map { "/file-$it.bin" }.toSet(),
                downloads,
                "refreshed pass must download every remote file, same as a clean pass",
            )
            // The refresh must NOT have manifested as a delete.
            assertEquals(
                0,
                report.plan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                },
                "a token refresh must never surface as a delete; plan=${report.plan}",
            )
        } finally {
            tracking.close()
        }
    }

    /**
     * INVARIANT (#161 safety): if the refresh itself fails (the 401 cannot be
     * recovered), the resulting enumeration is incomplete and the engine
     * suppresses EVERY delete — an auth failure must never be read as
     * remote-gone. Tracked rows survive; the next pass retries.
     *
     * Regression mode if removed/loosened: a transient refresh failure on a
     * long sync could be misread as "the whole remote vanished," propagating a
     * mass-delete — the most dangerous failure class the tracking engine exists
     * to prevent.
     */
    @Test
    fun `a failed refresh yields an incomplete pass that suppresses all deletes`() {
        provider.files["/a.txt"] = "a".toByteArray()
        provider.files["/b.txt"] = "b".toByteArray()

        // Pass 1: clean — both files become TrackedSynced and are downloaded.
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            assertEquals(2, tracking.countsByState()[TrackState.TrackedSynced])
        } finally {
            tracking.close()
        }

        // Pass 2: local copies vanish AND the token expires with refresh DISABLED
        // (the refresh round-trip itself fails). Without delete-suppression the
        // engine would see remote-present + local-absent → 2 PropagateLocalDelete.
        Files.delete(syncRoot.resolve("a.txt"))
        Files.delete(syncRoot.resolve("b.txt"))
        provider.refreshOnTokenExpiry = false
        provider.tokenExpiresAtDeltaCall = provider.deltaCallCount // next call expires

        val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)
        val tracking2 = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val report =
                TrackingEngine(provider, tracking2, syncRoot, batchGuard = permissive, dryRun = true).syncOnce()

            assertEquals(0, provider.refreshCount, "no successful refresh should have fired")
            assertFalse(
                report.remoteEnumerationComplete,
                "a failed refresh must mark the pass incomplete",
            )
            assertEquals(
                0,
                report.effectivePlan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                },
                "a failed refresh must suppress every delete from the effective plan",
            )
            assertEquals(
                2,
                tracking2.paths().size,
                "tracked rows must survive an auth-failure pass; got ${tracking2.paths()}",
            )
        } finally {
            tracking2.close()
        }
    }

    /**
     * INVARIANT (#161 observability): the refresh seam fires only when the
     * token actually expires — a clean run performs ZERO refreshes. This pins
     * the seam itself so the happy-path test's `refreshCount == 1` is
     * meaningful (not an artefact of the fake always refreshing).
     */
    @Test
    fun `a clean enumeration performs no token refresh`() {
        provider.files["/doc.txt"] = "hello".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot, dryRun = true).syncOnce()
            assertEquals(0, provider.refreshCount, "a non-expiring token must trigger no refresh")
        } finally {
            tracking.close()
        }
    }
}
