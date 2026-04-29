plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // UD-284: MDCContext-propagation regression test pins kotlinx-coroutines-slf4j
    // for the calling-side wrapping pattern that RelocateCommand uses.
    testImplementation(libs.kotlinx.coroutines.slf4j)
}

tasks.test {
    useJUnit()
}
