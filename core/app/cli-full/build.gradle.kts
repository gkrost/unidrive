plugins {
    kotlin("jvm")
    application
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.krost.unidrive.cli.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("unidrive-full")
    archiveClassifier.set("")
    mergeServiceFiles()
}

dependencies {
    implementation(project(":app:cli"))
}
