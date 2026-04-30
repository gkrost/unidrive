plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // UD-255: the shared correlation-id Ktor plugin lives here so every
    // HTTP-using provider can install it without a new inter-module dep.
    // ktor-client-core brings slf4j-api transitively for the plugin's DEBUG
    // logging.
    implementation(libs.ktor.client.core)

    // UD-343: shared `UnidriveJson` singleton lives here too so every
    // provider's wire-format parser can stop building its own Json {}
    // configuration block.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
