package org.krost.unidrive.tracking

import org.junit.Assume.assumeTrue

/**
 * Routine-vs-nightly live-test tiering.
 *
 * The tracking-set live tests fall into two cost classes:
 *
 *  - **Routine** — fast (<30 s), deterministic, no network or credentials.
 *    These run on every PR as part of `./gradlew check`. They drive the
 *    engine against [FakeTrackingProvider] and its fault-injection hooks,
 *    so the structural code paths (refresh recovery, throttle/incomplete
 *    handling, delete-suppression) are exercised without a real account.
 *
 *  - **Nightly** — slow (the real-account `TrackingEngineInternxtLiveTest`
 *    took over an hour against a ~195 k-file Internxt profile). These hit
 *    a real provider, need `UNIDRIVE_INTEGRATION_TESTS=true` + on-disk
 *    credentials, and are far too slow for a per-PR loop. They run only on the
 *    scheduled nightly CI job (`.github/workflows/nightly.yml`) or when an
 *    operator runs `./gradlew liveTestNightly` locally.
 *
 * The tier is selected by the `unidrive.liveTier` system property:
 *
 *   -Dunidrive.liveTier=routine   (default — fast tier only)
 *   -Dunidrive.liveTier=nightly   (also admits the slow real-account tier)
 *
 * The Gradle `liveTestRoutine` / `liveTestNightly` tasks set this property.
 * A bare `./gradlew test` / `check` inherits the default (`routine`), so the
 * slow tests stay skipped for ordinary developers and on PR CI.
 *
 * This is a JUnit-4 module (`useJUnit()` in build.gradle.kts), so tiering is a
 * system-property gate honoured via `org.junit.Assume`, NOT a JUnit-5
 * `@Tag`/`@EnabledIf`. A nightly-tier test on the routine tier reports as
 * SKIPPED (assumption failure), not FAILED, keeping the routine run green.
 */
object LiveTier {
    const val PROPERTY = "unidrive.liveTier"
    const val ROUTINE = "routine"
    const val NIGHTLY = "nightly"

    /** The active tier, read fresh each call so a test-launcher property change is honoured. */
    fun current(): String = System.getProperty(PROPERTY, ROUTINE).lowercase()

    /** True when the slow nightly tier is selected. */
    fun isNightly(): Boolean = current() == NIGHTLY

    /**
     * Gate a slow real-account live test on the nightly tier. On the routine
     * tier (the default) this raises a JUnit assumption failure, so the test
     * reports SKIPPED — never FAILED — and the routine wall-time stays bounded.
     *
     * Call FIRST in a `@Test` body, before any other gate (env var, creds),
     * so a routine run skips immediately without touching the network.
     */
    fun assumeNightly(testName: String) {
        if (!isNightly()) {
            val reason =
                "$testName: SKIPPED — nightly-tier live test not run on the '${current()}' tier " +
                    "(set -D$PROPERTY=$NIGHTLY, e.g. via `./gradlew liveTestNightly`)"
            println(reason)
            assumeTrue(reason, false)
        }
    }
}
