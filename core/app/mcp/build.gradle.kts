import java.time.Instant
import java.util.concurrent.TimeUnit

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
        // UD-733: GIT_DIRTY mirrors :app:cli — uncommitted-build warning at
        // startup. Same shape, separate generator because each module emits
        // its own BuildInfo class in its own package.
        val gitDirty =
            providers
                .exec {
                    commandLine("git", "status", "--porcelain")
                }.standardOutput.asText
                .map { it.trim().isNotEmpty() }

        doLast {
            val commit =
                try {
                    gitCommit.get().ifEmpty { "dev" }
                } catch (_: Exception) {
                    "dev"
                }
            val dirty =
                try {
                    gitDirty.get()
                } catch (_: Exception) {
                    false
                }
            val buildInstant = Instant.now().toString()
            val versionString =
                if (dirty) "$version ($commit-dirty)" else "$version ($commit)"
            val dir = outputDir.get().asFile.resolve("org/krost/unidrive/mcp")
            dir.mkdirs()
            dir.resolve("BuildInfo.kt").writeText(
                """
            |package org.krost.unidrive.mcp
            |
            |object BuildInfo {
            |    const val VERSION = "$version"
            |    const val COMMIT = "$commit"
            |    const val DIRTY = $dirty
            |    const val BUILD_INSTANT = "$buildInstant"
            |    fun versionString(): String = "$versionString"
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

    // UD-292 (inline): always-run, see :app:cli:deploy for rationale.
    outputs.upToDateWhen { false }

    // Capture at config time (Gradle 10: no Task.project at execution).
    val projectVersion = project.version.toString()
    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }

    doLast {
        val home = System.getProperty("user.home")
        val jarFile = shadowJarFile.get().asFile
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

        // ASCII-only — gradle daemon stdout encoding is platform default, see :app:cli equivalent.
        println("[deploy:mcp] starting -- version=$projectVersion jar=${jarFile.name} target=${if (isWindows) "Windows" else "Linux"}")

        if (isWindows) {
            deployWindows(home, jarFile, projectVersion)
        } else {
            deployLinux(home, jarFile, projectVersion)
        }

        println("[deploy:mcp] complete.")
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

    // UD-712 (inline-rewritten 2026-04-29): Kill any running MCP java
    // process before overwriting the JAR. See :app:cli:deploy for the
    // PowerShell-Get-CimInstance hang rationale; same fix applies here.
    // ProcessHandle.allProcesses() is JDK-9+ pure Java, no shell, no WMI.
    val targetJarName = jarFile.name
    val killed =
        ProcessHandle
            .allProcesses()
            .filter { ph ->
                val cmd = ph.info().commandLine().orElse("")
                cmd.contains(targetJarName) && !cmd.contains("gradle-")
            }.toList()
    killed.forEach { ph ->
        try {
            ph.destroyForcibly()
            ph.onExit().get(5L, TimeUnit.SECONDS)
            println("[deploy:mcp] killed running MCP process pid=${ph.pid()}")
        } catch (e: Exception) {
            println("[deploy:mcp] could not kill pid=${ph.pid()}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    if (killed.isEmpty()) println("[deploy:mcp] no running MCP process to kill -- proceeding to copy")

    jarFile.copyTo(targetJar, overwrite = true)
    println("[deploy:mcp] copied ${jarFile.absolutePath} -> ${targetJar.absolutePath} (${jarFile.length()} bytes)")

    launcher.writeText(
        // UD-258 (follow-up): force UTF-8 stdout/stderr so JSON-RPC payloads
        // with unicode (filenames, display names, errors) don't get mangled
        // by Windows' default OEM codepage.
        "@echo off\r\njava -Xmx6g -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar \"$targetJar\" %*\r\n",
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
        |exec java -Xmx6g --enable-native-access=ALL-UNNAMED -jar "$targetJar" "${'$'}@"
        """.trimMargin() + "\n",
    )
    launcher.setExecutable(true)

    println("Deployed unidrive-mcp $projectVersion:")
    println("  JAR:      $targetJar")
    println("  Launcher: $launcher")
}

dependencies {
    implementation(project(":app:core"))
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
    implementation(libs.kotlinx.coroutines.slf4j) // UD-294: MDCContext for relocate-tool MDC propagation
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
