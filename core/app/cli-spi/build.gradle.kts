plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// The CLI extension SPI (package org.krost.unidrive.cli.ext): the contract
// extensions implement and :app:cli loads (the `ts` commands are one). Kept
// out of :app:cli so an extension does not depend on the CLI that loads it
// (#560). The SPI's signatures expose CloudProvider (:app:core) and
// SyncConfig (:app:sync).
dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:sync"))
}
