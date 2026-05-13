plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("org.krost.unidrive.e2e.Main360Kt")
}

dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:sync"))

    implementation(libs.playwright)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    implementation(libs.picocli)

    testImplementation(kotlin("test"))
}

tasks.test { useJUnit() }
