plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.krost.unidrive.mcp.MainKt")
    // UD-258 (follow-up): same UTF-8 stdout/stderr forcing applied to the
    // CLI launcher. MCP emits JSON-RPC over stdio where filenames, user
    // display names, and error messages legitimately contain unicode.
    applicationDefaultJvmArgs =
        listOf(
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
}

val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        val outputDir = layout.buildDirectory.dir("generated/src/main/kotlin")
        outputs.dir(outputDir)
        outputs.upToDateWhen { false }

        // Capture at configuration time — Gradle 10 forbids Task.project access
        // during execution (configuration-cache compatibility).
        val version = project.version.toString()
        val gitCommit =
            providers
                .exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                }.standardOutput.asText
                .map { it.trim() }

        doLast {
            val commit =
                try {
                    gitCommit.get().ifEmpty { "dev" }
                } catch (_: Exception) {
                    "dev"
                }
            val dir = outputDir.get().asFile.resolve("org/krost/unidrive/mcp")
            dir.mkdirs()
            dir.resolve("BuildInfo.kt").writeText(
                """
            |package org.krost.unidrive.mcp
            |
            |object BuildInfo {
            |    const val VERSION = "$version"
            |    const val COMMIT = "$commit"
            |    fun versionString(): String = "$version ($commit)"
            |}
                """.trimMargin() + "\n",
            )
        }
    }

sourceSets.main {
    kotlin.srcDir(generateBuildInfo.map { layout.buildDirectory.dir("generated/src/main/kotlin") })
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

tasks.shadowJar {
    archiveBaseName.set("unidrive-mcp")
    archiveClassifier.set("")
    mergeServiceFiles()
}

fun run(
    vararg cmd: String,
    ignoreExit: Boolean = false,
): String {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    if (!ignoreExit && proc.exitValue() != 0) {
        throw GradleException("Command failed (${proc.exitValue()}): ${cmd.toList()}\n$output")
    }
    return output.trim()
}

tasks.register("deploy") {
    dependsOn(tasks.shadowJar)
    group = "distribution"
    description = "Deploy MCP server JAR and launcher"

    // Capture at config time (Gradle 10: no Task.project at execution).
    val projectVersion = project.version.toString()
    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }

    doLast {
        val home = System.getProperty("user.home")
        val jarFile = shadowJarFile.get().asFile
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

        if (isWindows) {
            deployWindows(home, jarFile, projectVersion)
        } else {
            deployLinux(home, jarFile, projectVersion)
        }
    }
}

fun deployWindows(
    home: String,
    jarFile: File,
    projectVersion: String,
) {
    val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
    val libDir = file("$localAppData\\unidrive")
    val binDir = file("$home\\.local\\bin")
    val targetJar = file("$libDir\\${jarFile.name}")
    val launcher = file("$binDir\\unidrive-mcp.cmd")

    libDir.mkdirs()
    binDir.mkdirs()

    // UD-712: Kill any running MCP java process before overwriting the JAR.
    // Matches java.exe whose cmdline references the unidrive-mcp shadow jar
    // (MCP servers are spawned by Claude Desktop / other clients, so they
    // carry no window title and the old taskkill-by-title approach missed
    // them entirely — FileAlreadyExistsException on the copy).
    run(
        "powershell",
        "-NoProfile",
        "-Command",
        // Filter uses PowerShell escaped single-quotes: Windows
        // ProcessBuilder strips embedded double-quotes from command-line
        // args, which breaks `-Filter "Name='java.exe'"`. The doubled
        // single-quote (`''`) is PowerShell's in-string escape for `'`.
        "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | " +
            "Where-Object { \$_.CommandLine -match 'unidrive-mcp-[^ ]*\\.jar' } | " +
            "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }",
        ignoreExit = true,
    )

    jarFile.copyTo(targetJar, overwrite = true)

    launcher.writeText(
        // UD-258 (follow-up): force UTF-8 stdout/stderr so JSON-RPC payloads
        // with unicode (filenames, display names, errors) don't get mangled
        // by Windows' default OEM codepage.
        "@echo off\r\njava -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar \"$targetJar\" %*\r\n",
    )

    println("Deployed unidrive-mcp $projectVersion (Windows):")
    println("  JAR:      $targetJar")
    println("  Launcher: $launcher")
    println()
    println("NOTE: Ensure ${binDir.absolutePath} is on your PATH.")
}

fun deployLinux(
    home: String,
    jarFile: File,
    projectVersion: String,
) {
    val libDir = file("$home/.local/lib/unidrive")
    val binDir = file("$home/.local/bin")
    val targetJar = file("$libDir/${jarFile.name}")
    val launcher = file("$binDir/unidrive-mcp")

    libDir.mkdirs()
    binDir.mkdirs()

    jarFile.copyTo(targetJar, overwrite = true)

    launcher.writeText(
        """
        |#!/usr/bin/env bash
        |exec java --enable-native-access=ALL-UNNAMED -jar "$targetJar" "${'$'}@"
        """.trimMargin() + "\n",
    )
    launcher.setExecutable(true)

    println("Deployed unidrive-mcp $projectVersion:")
    println("  JAR:      $targetJar")
    println("  Launcher: $launcher")
}

dependencies {
    implementation(project(":app:core"))
    implementation(project(":providers:hidrive"))
    implementation(project(":providers:internxt"))
    implementation(project(":providers:localfs"))
    implementation(project(":providers:onedrive"))
    implementation(project(":providers:rclone"))
    implementation(project(":providers:s3"))
    implementation(project(":providers:sftp"))
    implementation(project(":providers:webdav"))
    implementation(project(":app:sync"))
    implementation(project(":app:xtra"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
