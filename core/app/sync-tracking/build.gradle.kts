plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:cli"))
    // SyncConfig data type returned by CliServices.loadSyncConfig() lives in :app:sync.
    // If a future change lifts SyncConfig to :app:core, this dep goes away.
    implementation(project(":app:sync"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.picocli)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Live-integration test pointing the engine at a real Internxt provider.
    // The test itself skips cleanly when UNIDRIVE_INTEGRATION_TESTS != true,
    // so this dep is dormant for developers without an Internxt account.
    testImplementation(project(":providers:internxt"))
    // Live-integration test pointing the engine at a real OneDrive profile.
    // Same skip-cleanly gating as the Internxt live test.
    testImplementation(project(":providers:onedrive"))
}

// Live-test tiering. The tier is selected by the `unidrive.liveTier`
// system property, read by `LiveTier` in the test source set:
//
//   routine  (default) — fast, deterministic, no network/credentials. Runs on
//                        every PR via `./gradlew check`. Includes the engine
//                        refresh and throttle tests, which drive
//                        FakeTrackingProvider's fault-injection seams.
//   nightly            — also admits the slow real-account live tests
//                        (TrackingEngine{Internxt,OneDrive}LiveTest), which took
//                        over an hour against a real profile. Run by the
//                        scheduled `.github/workflows/nightly.yml` job or
//                        `./gradlew liveTestNightly`.
//
// JUnit 4 module (`useJUnit()`): the slow tests gate via `LiveTier.assumeNightly`
// (an `org.junit.Assume` skip), so on the routine tier they report SKIPPED, not
// FAILED. There is no JUnit-5 `@Tag` here by design.
val liveTierProperty = "unidrive.liveTier"

// The default `test` task (and therefore `check`) runs the ROUTINE tier: the
// slow real-account tests skip via assumeNightly even if a developer has
// UNIDRIVE_INTEGRATION_TESTS=true exported. Forcing the property here is the
// guard that keeps `./gradlew check` bounded.
tasks.test {
    useJUnit()
    systemProperty(liveTierProperty, System.getProperty(liveTierProperty, "routine"))
    // `check` runs this task, so it stays as quiet as the other modules' tests: failures only. The
    // routine tier's fake-driven tests log their simulated failures at WARN and used to echo every test
    // and every line of it into the build log. Output is still in the test report. The live-test tasks
    // below keep the full echo: there the test's println IS the answer the operator wants (plan size,
    // adopted count, refresh/throttle observability).
    testLogging {
        events("failed")
    }
}

// Explicit routine-tier task. Functionally the same as `test` with the
// default tier, but named so CI and operators can invoke the fast live-test
// loop unambiguously. Reuses the test source set and classpath.
tasks.register<Test>("liveTestRoutine") {
    group = "verification"
    description = "Fast live-test tier: fake-driven engine tests incl. refresh + throttle. Runs on every PR."
    useJUnit()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty(liveTierProperty, "routine")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// Slow nightly-tier task. Admits the real-account live tests
// (TrackingEngine{Internxt,OneDrive}LiveTest) in addition to the routine set.
// Those tests still self-skip unless UNIDRIVE_INTEGRATION_TESTS=true and the
// provider credentials are present, so this task is green on stock infra and
// only does real work when a real account is wired up (the scheduled CI job).
tasks.register<Test>("liveTestNightly") {
    group = "verification"
    description = "Slow live-test tier: includes real-account TrackingEngine live tests. For the nightly CI job."
    useJUnit()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty(liveTierProperty, "nightly")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
