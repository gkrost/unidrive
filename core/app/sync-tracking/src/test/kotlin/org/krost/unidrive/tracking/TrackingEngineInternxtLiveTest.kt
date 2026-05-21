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

        /**
         * Returns null when the test can run, or a human-readable reason string
         * otherwise. Reason is surfaced via `assumeTrue` so a skipped run reports
         * SKIPPED with the cause visible in the JUnit XML / Gradle test report,
         * rather than silently early-returning as PASSED.
         */
        private fun skipReason(): String? {
            if (!shouldRun) {
                return "UNIDRIVE_INTEGRATION_TESTS env var is not 'true' " +
                    "(set it before launching gradle; PowerShell: \$env:UNIDRIVE_INTEGRATION_TESTS = 'true')"
            }
            val config = InternxtConfig()
            val credFile = config.tokenPath.resolve("credentials.json")
            if (!Files.exists(credFile)) {
                return "Internxt credentials not found at $credFile " +
                    "(run `unidrive -p <profile> auth` first)"
            }
            return null
        }
    }

    @Test
    fun `tracking engine against live Internxt — lemma holds, plan is downloads-only`() {
        val reason = skipReason()
        if (reason != null) {
            // Mirror the skip reason to stdout so showStandardStreams=true
            // surfaces it in the gradle console (the XML report has it via
            // the AssumptionViolatedException message, but stdout is what
            // most operators actually look at).
            println("TrackingEngineInternxtLiveTest: SKIPPED — $reason")
        }
        assumeTrue("Live Internxt test skipped: $reason", reason == null)

        val workDir = createTempDirectory("ts-internxt-live")
        val syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        val dbPath = workDir.resolve("tracking.db")

        val provider = InternxtProvider()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            runBlocking { provider.authenticate() }
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
