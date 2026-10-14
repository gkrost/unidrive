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

// #891: the retained-heap probe of a full enumeration (fresh vs resumed), opt-in so `check` is unchanged.
// Sizes via -Psizes=100000,200000,400000 (default 100k/200k/400k inside the probe).
tasks.register<JavaExec>("heapProbe") {
    group = "verification"
    description = "Retained heap of a full enumeration, fresh vs resumed; fails past the resumed/fresh ratio."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.krost.unidrive.engine.HeapProbeKt")
    jvmArgs("-Xmx2g")
    args = (project.findProperty("sizes") as String?)?.split(',') ?: emptyList()
}
