plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))
    // The shared engine core: state repository, path and scope rules, the remote-operation guards,
    // the gather and the enumeration MountEngine runs on (#560 U2, U3). No :app:sync: the mount
    // front-end does not depend on the mirror engine (checkModuleEdges).
    implementation(project(":app:engine-core"))
    implementation(libs.kotlinx.coroutines.core)

    // slf4j-api for the upload-failure WARN line (same route app:sync takes).
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // #560 U3: the tests build hydration on a SyncEngine (the host of the shared core today) through
    // HydrationImpl's compatibility constructor. Test scope only.
    testImplementation(project(":app:sync"))
}

tasks.test {
    useJUnit()
}
