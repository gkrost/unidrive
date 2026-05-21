package org.krost.unidrive.tracking

import kotlinx.coroutines.runBlocking
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

        private fun checkEnabled(): Boolean {
            if (!shouldRun) return false
            val config = InternxtConfig()
            val credFile = config.tokenPath.resolve("credentials.json")
            return Files.exists(credFile)
        }
    }

    @Test
    fun `tracking engine against live Internxt — lemma holds, plan is downloads-only`() {
        if (!checkEnabled()) return

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
