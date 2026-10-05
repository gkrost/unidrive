plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// #560 (4A.2): the primitives the mirror engine (:app:sync) and the mount
// operations (:app:hydration) both use — the state repository, path and scope
// rules, and the guards in front of a remote operation. Depends on the
// provider SPI in :app:core only; checkModuleEdges (root build) enforces that.
dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    // slf4j-api for StateDatabase's log lines (same route app:sync takes).
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
