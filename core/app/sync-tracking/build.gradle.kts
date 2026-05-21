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
}

tasks.test {
    useJUnit()
}
