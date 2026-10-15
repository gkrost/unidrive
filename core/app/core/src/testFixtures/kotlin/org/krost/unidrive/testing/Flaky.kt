package org.krost.unidrive.testing

/**
 * JUnit 4 category of the quarantined tests (docs/dev/testing-flaky-policy.md).
 *
 * `test` (and so `check`) excludes this category; `flakyTest` runs only it and never fails the build. The root build
 * script (`core/build.gradle.kts`) wires both, and `checkFlakyQuarantine` (part of `check`) refuses a quarantined test
 * that does not carry [Flaky].
 *
 * Quarantine a test with both lines together, in this order:
 *
 *     @Category(FlakyTests::class)
 *     @Flaky(issue = 832, reason = "...", firstFailure = "https://github.com/gkrost/unidrive/actions/runs/...")
 *
 * Un-quarantine it by deleting both lines once the issue's fix has held.
 */
interface FlakyTests

/**
 * Why a test sits in [FlakyTests]: the open issue that tracks the fix, what makes the test non-deterministic, and the
 * first CI run that showed it. All three are required; the build checks them.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Flaky(
    val issue: Int,
    val reason: String,
    val firstFailure: String,
)
