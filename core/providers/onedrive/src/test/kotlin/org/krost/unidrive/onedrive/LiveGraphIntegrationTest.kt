package org.krost.unidrive.onedrive

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.krost.unidrive.http.HttpRetryBudget
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-808 / docs/dev/oauth-test-injection.md — live Graph integration test that
 * consumes a short-lived access token passed via the `UNIDRIVE_TEST_ACCESS_TOKEN`
 * environment variable. The `unidrive-test-oauth` MCP (scripts/dev/oauth-mcp/)
 * is the canonical way to mint that token; an agent or operator can also paste
 * one from `unidrive -p <profile> auth --print-token` if that flag ever exists.
 *
 * Unlike [OneDriveIntegrationTest] these tests:
 *   - do not read `token.json` off disk (the MSIX sandbox hides %APPDATA%\unidrive
 *     from native-Windows Python, so CI and sandboxed runs can't rely on it);
 *   - do not run the full SyncEngine — they exercise `GraphApiService` directly;
 *   - are gated behind the `UNIDRIVE_TEST_ACCESS_TOKEN` env var being set, so a
 *     developer without the MCP still gets a clean `assumeTrue` skip and green
 *     `./gradlew :providers:onedrive:test`.
 *
 * These tests make real HTTP calls. Keep the assertions forgiving — they check
 * that the wiring works, not that Graph returns specific bytes.
 */
class LiveGraphIntegrationTest {
    companion object {
        private val accessToken: String? = System.getenv("UNIDRIVE_TEST_ACCESS_TOKEN")

        private fun enabled(): Boolean = !accessToken.isNullOrBlank()
    }

    private lateinit var service: GraphApiService

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "UNIDRIVE_TEST_ACCESS_TOKEN not set — skipping live Graph tests. " +
                "Start the unidrive-test-oauth MCP (scripts/dev/oauth-mcp/) and inject " +
                "a grant_token() result into the environment to run these.",
            enabled(),
        )
        val token = accessToken!!
        service =
            GraphApiService(OneDriveConfig()) { _ ->
                // The tokenProvider lambda is called fresh on every request. Ignore
                // forceRefresh — we never refresh in this harness; if the injected
                // token expires the test fails loudly, which is the correct signal.
                token
            }
    }

    @Test
    fun `getDrive round-trip succeeds with injected access token`() =
        runTest {
            val drive = service.getDrive()
            assertTrue(drive.id.isNotEmpty(), "drive.id must be populated")
            assertNotNull(drive.driveType, "drive.driveType must be populated")
            println(
                "LiveGraphIntegrationTest: drive.id=${drive.id.takeLast(8)} " +
                    "type=${drive.driveType} owner=${drive.owner?.user?.displayName}",
            )
        }

    @Test
    fun `HttpRetryBudget stays engaged under parallel getDrive load`() =
        runTest {
            // Fire N concurrent getDrive() calls and report the HttpRetryBudget's
            // state afterwards. This is a smoke test for the UD-232 wiring — it
            // does not assert that the circuit opened (Graph may or may not
            // throttle depending on load and tenant), but it does assert that
            // no call crashed and that currentConcurrency is still in bounds.
            val budget = HttpRetryBudget(maxConcurrency = 8)
            val svc =
                GraphApiService(OneDriveConfig(), budget) { _ ->
                    accessToken!!
                }
            val n = 24
            val results =
                coroutineScope {
                    (1..n).map { async { runCatching { svc.getDrive() } } }.awaitAll()
                }
            val succeeded = results.count { it.isSuccess }
            val failed = results.count { it.isFailure }
            println(
                "LiveGraphIntegrationTest: parallel load n=$n succeeded=$succeeded " +
                    "failed=$failed currentConcurrency=${budget.currentConcurrency()} " +
                    "resumeAfterMs=${budget.resumeAfterEpochMs()}",
            )
            assertTrue(
                succeeded >= 1,
                "at least one of $n parallel getDrive() calls should succeed",
            )
            assertTrue(
                budget.currentConcurrency() in 1..8,
                "budget.currentConcurrency() must stay in [1, 8], was ${budget.currentConcurrency()}",
            )
            svc.close()
        }
}
