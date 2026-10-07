plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))
    // The state repository, path and scope rules, and the remote-operation guards
    // shared with the mount operations (#560 U2).
    implementation(project(":app:engine-core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // #560 U3: the tests of the mount operations that moved to MountEngine still call them on a
    // SyncEngine, through MountEngineTestAdapters.kt. Test scope only; checkModuleEdges forbids the
    // main edge :app:sync -> :app:hydration.
    testImplementation(project(":app:hydration"))
    // UD-284: MDCContext-propagation regression test pins kotlinx-coroutines-slf4j
    // for the calling-side wrapping pattern that RelocateCommand uses.
    testImplementation(libs.kotlinx.coroutines.slf4j)
}

tasks.test {
    useJUnit()
}
