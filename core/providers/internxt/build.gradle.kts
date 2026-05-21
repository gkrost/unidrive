plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:sync"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // socket.io client for the Internxt NOTIFICATIONS_URL change-feed (real-time
    // wake-signal). Transitive: engine.io-client + okhttp3 + org.json. MIT.
    implementation(libs.socket.io.client)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // UD-203: MockEngine for request-id propagation tests.
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnit()
    environment("UNIDRIVE_INTEGRATION_TESTS", System.getenv("UNIDRIVE_INTEGRATION_TESTS") ?: "false")
}
