package org.krost.unidrive.tracking

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.internxt.InternxtProvider
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live-integration smoke test for the tracking-set engine against a real
 * Internxt profile. Verifies the structural invariants hold against real
 * provider data, not just `FakeTrackingProvider`:
 *
 *   1. The lemma — zero delete actions emitted against an empty local
 *      `sync_root`, no matter what the remote contains.
 *   2. Every action emitted is a `DownloadRemote` (untracked + pure-remote
 *      → DownloadRemote per the reconciler's case table).
 *   3. The engine doesn't crash on Internxt's actual delta-page shape
 *      (folders skipped, file metadata mapped, no NPE on optional fields).
 *
 * Gated on `UNIDRIVE_INTEGRATION_TESTS=true` + Internxt `credentials.json`
 * being present, mirroring `InternxtIntegrationTest` at
 * `core/providers/internxt/src/test/.../InternxtIntegrationTest.kt`.
 *
 * Skips cleanly without credentials so `./gradlew check` stays green for
 * developers without an Internxt account. Operator-facing output (final
 * `println`) reports plan size so a human running this can sanity-check
 * "tracking-set saw the same files the legacy engine would have."
 */
class TrackingEngineInternxtLiveTest {
    companion object {
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false
    }

    @Test
    fun `tracking engine against live Internxt — lemma holds, plan is downloads-only`() {
        // Gate 0 — nightly tier only. A full `syncOnce()` against a real
        // Internxt profile took over an hour on a large account; far too slow
        // for the routine (per-PR) tier. SKIPPED on the routine tier; runs on
        // `./gradlew liveTestNightly` or the scheduled nightly CI job.
        LiveTier.assumeNightly("TrackingEngineInternxtLiveTest")

        // Gate 1 — env var must be enabled. SKIPPED with reason on miss.
        if (!shouldRun) {
            val reason = "UNIDRIVE_INTEGRATION_TESTS env var is not 'true' " +
                "(set it before launching gradle; PowerShell: \$env:UNIDRIVE_INTEGRATION_TESTS = 'true')"
            println("TrackingEngineInternxtLiveTest: SKIPPED — $reason")
            assumeTrue("Live Internxt test skipped: $reason", false)
            return
        }

        // Gate 2 — provider must actually authenticate. Doesn't pre-check
        // credentials.json existence: credentials may live in the vault
        // rather than as a plain file. The legacy file-existence check
        // misses vault-stored profiles entirely (the user's `internxt`
        // profile works for `unidrive status` because state.db has cached
        // metadata, even when credentials.json is absent because the vault
        // holds the real credentials). Attempting authenticate() lets the
        // provider's own credential lookup decide.
        val provider = InternxtProvider()
        try {
            runBlocking { provider.authenticate() }
        } catch (e: Exception) {
            val reason = "InternxtProvider.authenticate() failed: " +
                "${e.message ?: e::class.java.simpleName}. " +
                "If credentials are vault-stored, the no-arg InternxtProvider() " +
                "may not pick them up — `unidrive -p internxt auth` writes " +
                "credentials.json at the default path which the no-arg constructor reads. " +
                "Default path: ${InternxtConfig().tokenPath.resolve("credentials.json")}"
            println("TrackingEngineInternxtLiveTest: SKIPPED — $reason")
            assumeTrue("Live Internxt test skipped: $reason", false)
            return
        }

        val workDir = createTempDirectory("ts-internxt-live")
        val syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        val dbPath = workDir.resolve("tracking.db")

        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            val engine = TrackingEngine(provider, tracking, syncRoot, dryRun = true)
            val report = engine.syncOnce()

            // INVARIANT 1 — the lemma against real data.
            // Empty local + empty tracking + whatever-remote-has → ZERO deletes.
            val deletes =
                report.plan.count {
                    it is ReconcileAction.PropagateLocalDelete ||
                        it is ReconcileAction.PropagateRemoteDelete
                }
            assertEquals(
                0,
                deletes,
                "tracking-set against empty local must NEVER plan deletes; got: ${report.plan}",
            )

            // INVARIANT 2 — every planned action is DownloadRemote.
            // Empty local + populated remote + empty tracking → all paths are
            // untracked + pure-remote → DownloadRemote per the reconciler.
            val nonDownload = report.plan.filterNot { it is ReconcileAction.DownloadRemote }
            assertTrue(
                nonDownload.isEmpty(),
                "non-download actions against empty local sync_root: $nonDownload",
            )

            // INVARIANT 3 — no collisions on first-scan of an empty local.
            // Adopt-on-content-match needs both sides present; impossible here.
            assertTrue(
                report.collisions.isEmpty(),
                "first-scan of empty local should produce no collisions; got: ${report.collisions}",
            )

            // Operator-facing summary: a human running this should see plan
            // size that roughly matches their Internxt file count.
            println(
                "TrackingEngineInternxtLiveTest: plan size=${report.plan.size} " +
                    "(all DownloadRemote); adopted=${report.adopted.size} (should be 0); " +
                    "collisions=${report.collisions.size} (should be 0)",
            )
        } finally {
            tracking.close()
            workDir.toFile().deleteRecursively()
        }
    }
}
