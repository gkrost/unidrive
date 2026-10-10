plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    // The engine test fakes (FakeCloudProvider) are shared with the mount front-end's tests in
    // :app:hydration, which build their SyncEngine host on them.
    `java-test-fixtures`
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

    // FakeCloudProvider implements the provider SPI and suspends (delay).
    testFixturesApi(project(":app:core"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // UD-284: MDCContext-propagation regression test pins kotlinx-coroutines-slf4j
    // for the calling-side wrapping pattern that RelocateCommand uses.
    testImplementation(libs.kotlinx.coroutines.slf4j)
}

tasks.test {
    useJUnit()
}
