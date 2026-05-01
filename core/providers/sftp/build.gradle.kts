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

    // Apache MINA SSHD — JVM-native SFTP client (no native binary required)
    implementation(libs.sshd.sftp)
    implementation(libs.sshd.core)

    // BouncyCastle: required by MINA for OpenSSH-format Ed25519 private keys
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.bcprov.jdk18on)

    // i2p EdDSA: required by MINA's OpenSSHEd25519PrivateKeyEntryDecoder
    implementation(libs.eddsa)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    environment("UNIDRIVE_INTEGRATION_TESTS", System.getenv("UNIDRIVE_INTEGRATION_TESTS") ?: "false")
}
