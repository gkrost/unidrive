plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// The CLI extension SPI (package org.krost.unidrive.cli.ext): the contract
// :app:cli loads extensions through and :app:sync-tracking implements. Kept
// out of :app:cli so an extension does not depend on the CLI that loads it
// (#560). The SPI's signatures expose CloudProvider (:app:core) and
// SyncConfig (:app:sync).
dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:sync"))
}
