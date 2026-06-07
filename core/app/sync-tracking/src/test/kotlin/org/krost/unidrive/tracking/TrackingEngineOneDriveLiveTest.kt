package org.krost.unidrive.tracking

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.krost.unidrive.onedrive.OneDriveConfig
import org.krost.unidrive.onedrive.OneDriveProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live-integration smoke test for the tracking-set engine against the
 * real OneDrive profile `posteo_onedrive`. Mirrors the structural
 * assertions in `TrackingEngineInternxtLiveTest`:
 *
 *   1. The lemma — zero delete actions emitted against an empty local
 *      `sync_root`, no matter what the remote contains.
 *   2. Every action emitted is a `DownloadRemote` (untracked + pure-remote
 *      → DownloadRemote per the reconciler's case table).
 *   3. The engine doesn't crash on OneDrive's actual delta-page shape
 *      (folders skipped, file metadata mapped, no NPE on optional fields).
 *
 * Gated on `UNIDRIVE_INTEGRATION_TESTS=true` + `token.json` being present
 * under `~/.config/unidrive/posteo_onedrive/`. Uses the
 * `OneDriveDeltaIntegrationTest` pattern (read token.json off disk;
 * provider's auth-refresh handles 401s mid-run) so a long enumeration
 * can survive the standard 60-min OneDrive token TTL.
 *
 * Skips cleanly without credentials so `./gradlew check` stays green for
 * developers without a OneDrive profile.
 */
class TrackingEngineOneDriveLiveTest {
    companion object {
        private const val PROFILE = "posteo_onedrive"
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false

        private fun profileTokenDir(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", PROFILE)
        }
    }

    @Test
    fun `tracking engine against live OneDrive — lemma holds, plan is downloads-only`() {
        // Gate 0 — nightly tier only. This pass walks the full OneDrive
        // delta against a real profile; it shares the slow-tier cost class with
        // the Internxt live test and must stay off the routine (per-PR) tier.
        // SKIPPED on the routine tier; runs on `./gradlew liveTestNightly` or the
        // scheduled nightly CI job.
        LiveTier.assumeNightly("TrackingEngineOneDriveLiveTest")

        // Gate 1 — env var must be enabled. SKIPPED with reason on miss.
        if (!shouldRun) {
            val reason = "UNIDRIVE_INTEGRATION_TESTS env var is not 'true' " +
                "(set it before launching gradle; PowerShell: \$env:UNIDRIVE_INTEGRATION_TESTS = 'true')"
            println("TrackingEngineOneDriveLiveTest: SKIPPED — $reason")
            assumeTrue("Live OneDrive test skipped: $reason", false)
            return
        }

        // Gate 2 — token.json for the posteo_onedrive profile must exist.
        // The provider's TokenManager handles refresh-on-401, so we don't
        // need a separately-supplied bearer token; the on-disk token is
        // sufficient even if it's near expiry at start-of-run.
        val tokenDir = profileTokenDir()
        val tokenFile = tokenDir.resolve("token.json")
        if (!Files.exists(tokenFile)) {
            val reason = "token.json not present at $tokenFile " +
                "(run `unidrive -p $PROFILE auth` to populate it)"
            println("TrackingEngineOneDriveLiveTest: SKIPPED — $reason")
            assumeTrue("Live OneDrive test skipped: $reason", false)
            return
        }

        val config = OneDriveConfig(tokenPath = tokenDir)
        val provider = OneDriveProvider(config)
        try {
            runBlocking { provider.authenticate() }
        } catch (e: Exception) {
            val reason = "OneDriveProvider.authenticate() failed: " +
                "${e.message ?: e::class.java.simpleName}"
            println("TrackingEngineOneDriveLiveTest: SKIPPED — $reason")
            assumeTrue("Live OneDrive test skipped: $reason", false)
            return
        }

        val workDir = createTempDirectory("ts-onedrive-live")
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
            val nonDownload = report.plan.filterNot { it is ReconcileAction.DownloadRemote }
            assertTrue(
                nonDownload.isEmpty(),
                "non-download actions against empty local sync_root: $nonDownload",
            )

            // INVARIANT 3 — no collisions on first-scan of an empty local.
            assertTrue(
                report.collisions.isEmpty(),
                "first-scan of empty local should produce no collisions; got: ${report.collisions}",
            )

            // Operator-facing summary.
            println(
                "TrackingEngineOneDriveLiveTest: plan size=${report.plan.size} " +
                    "(all DownloadRemote); adopted=${report.adopted.size} (should be 0); " +
                    "collisions=${report.collisions.size} (should be 0); " +
                    "remoteEnumerationComplete=${report.remoteEnumerationComplete}",
            )
        } finally {
            tracking.close()
            try {
                provider.close()
            } catch (_: Exception) {
                // best-effort cleanup
            }
            workDir.toFile().deleteRecursively()
        }
    }
}
