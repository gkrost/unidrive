import java.time.Instant
import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.krost.unidrive.cli.MainKt")
    // UD-258: force UTF-8 on stdout/stderr so Windows JVMs don't encode
    // glyphs as cp1252 `?`. Consumed by startScripts and the custom
    // deploy-task launcher below.
    applicationDefaultJvmArgs =
        listOf(
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
        )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        val outputDir = layout.buildDirectory.dir("generated/src/main/kotlin")
        outputs.dir(outputDir)
        // Always regenerate — git commit changes without source changes
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
        // UD-733: GIT_DIRTY surfaces uncommitted-build warnings at startup so
        // users don't file bug reports against transient WIP state. `git
        // status --porcelain` is empty iff the worktree is clean; output
        // length collapses to a single boolean.
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
            val dir = outputDir.get().asFile.resolve("org/krost/unidrive/cli")
            dir.mkdirs()
            dir.resolve("BuildInfo.kt").writeText(
                """
            |package org.krost.unidrive.cli
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
    archiveBaseName.set("unidrive")
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
    description = "Build fat JAR and deploy to local system"

    // UD-292 (inline): without this Gradle 9's task-skipping
    // heuristics elide doLast {} blocks on tasks that declare no
    // outputs. The CLI deploy was silently NOT running between Gradle
    // task graph evaluations even with --rerun-tasks, leaving the
    // %LOCALAPPDATA%\unidrive\unidrive-*.jar stale relative to the
    // build/libs jar. Always-run is the right semantic here: deploy
    // must copy on every invocation.
    outputs.upToDateWhen { false }

    // Capture at config time (Gradle 10: no Task.project at execution).
    val projectVersion = project.version.toString()
    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }

    doLast {
        val home = System.getProperty("user.home")
        val jarFile = shadowJarFile.get().asFile
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

        println("[deploy] starting — version=$projectVersion jar=${jarFile.name} target=${if (isWindows) "Windows" else "Linux"}")

        if (isWindows) {
            deployWindows(home, jarFile, projectVersion)
        } else {
            deployLinux(home, jarFile, projectVersion)
        }

        println("[deploy] complete.")
    }
}

fun deployWindows(
    home: String,
    jarFile: File,
    projectVersion: String,
) {
    val localAppData = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
    val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
    val libDir = file("$localAppData\\unidrive")
    val binDir = file("$home\\.local\\bin")
    val targetJar = file("$libDir\\${jarFile.name}")
    val launcher = file("$binDir\\unidrive.cmd")
    val startupDir = file("$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Startup")
    val startupVbs = file("$startupDir\\unidrive.vbs")

    libDir.mkdirs()
    binDir.mkdirs()

    // UD-712 (inline-rewritten 2026-04-29): Kill any running unidrive java
    // process before overwriting the JAR. The previous Get-CimInstance
    // PowerShell subroutine intermittently hung the gradle daemon (WMI /
    // DCOM auth stalls under load) — the deploy task body would never
    // reach the copyTo() below, the build exited cleanly without a
    // BUILD SUCCESSFUL marker, and the AppData jar stayed stale. The
    // user spent a session debugging "deploy didn't deploy."
    //
    // Replaced with ProcessHandle.allProcesses() — pure Java since JDK 9,
    // no shell, no WMI, no PowerShell. Filters by commandLine substring
    // (more reliable than the old WINDOWTITLE filter that missed
    // background daemons launched from the startup VBS).
    val targetJarName = jarFile.name
    val killed =
        ProcessHandle
            .allProcesses()
            .filter { ph ->
                val cmd = ph.info().commandLine().orElse("")
                // Match the CLI shadow-jar specifically, not the MCP
                // sibling (which is killed by :app:mcp:deploy) and not
                // gradle's own daemon, which always has gradle on its
                // command line.
                cmd.contains(targetJarName) && !cmd.contains("gradle-")
            }.toList()
    killed.forEach { ph ->
        try {
            ph.destroyForcibly()
            ph.onExit().get(5L, TimeUnit.SECONDS)
            println("[deploy] killed running CLI process pid=${ph.pid()}")
        } catch (e: Exception) {
            println("[deploy] could not kill pid=${ph.pid()}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    if (killed.isEmpty()) println("[deploy] no running CLI process to kill — proceeding to copy")

    jarFile.copyTo(targetJar, overwrite = true)
    println("[deploy] copied ${jarFile.absolutePath} -> ${targetJar.absolutePath} (${jarFile.length()} bytes)")

    // UD-270: route the cmd shim through PowerShell. cmd.exe's batch-file
    // CTRL-C handler intercepts SIGINT and — *after* the JVM has already
    // died — emits "Terminate batch job (Y/N)?" / "Batchvorgang abbrechen
    // (J/N)?". Pure noise: the answer has no effect on anything that's
    // still running. PowerShell exits cleanly back to the parent shell on
    // CTRL-C with no prompt.
    val ps1Launcher = file("$libDir\\unidrive.ps1")
    ps1Launcher.writeText(
        """
        |# UD-270: PowerShell launcher. Invoked by ${'$'}binDir\unidrive.cmd
        |# so CTRL-C from the user's shell exits cleanly without cmd.exe's
        |# trailing "Terminate batch job?" prompt.
        |& java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar "$targetJar" @args
        |exit ${'$'}LASTEXITCODE
        """.trimMargin() + "\r\n",
    )

    launcher.writeText(
        """
        |@echo off
        |REM UD-270: routed through PowerShell to suppress the cmd.exe
        |REM "Terminate batch job (Y/N)?" prompt on CTRL-C. powershell.exe
        |REM (Windows PowerShell 5.1) is always present on Windows 10/11;
        |REM no need for pwsh.
        |powershell -NoProfile -ExecutionPolicy Bypass -File "${ps1Launcher.absolutePath}" %*
        """.trimMargin() + "\r\n",
    )

    // Batch wrapper — restart loop with sentinel-based stop
    val batchWrapper = file("$libDir\\unidrive-watch.cmd")
    val jarPath = targetJar.absolutePath.replace("\\", "\\\\")
    val sentinelPath = "$localAppData\\\\unidrive\\\\stop"
    batchWrapper.writeText(
        "@echo off\r\n" +
            ":loop\r\n" +
            "java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar " +
            "\"${targetJar.absolutePath}\" sync --watch\r\n" +
            "if exist \"${localAppData}\\unidrive\\stop\" (\r\n" +
            "    del \"${localAppData}\\unidrive\\stop\"\r\n" +
            "    exit /b 0\r\n" +
            ")\r\n" +
            "timeout /t 30 /nobreak >nul\r\n" +
            "goto loop\r\n",
    )

    // Startup VBS script — launches batch wrapper hidden (no console window)
    val batchPath = batchWrapper.absolutePath.replace("\\", "\\\\")
    val vbs =
        "Set ws = CreateObject(\"WScript.Shell\")\r\n" +
            "ws.Run \"\"\"\" & \"$batchPath\" & \"\"\"\", 0, False\r\n"
    startupVbs.writeText(vbs)

    println("Deployed unidrive $projectVersion (Windows):")
    println("  JAR:      $targetJar")
    println("  Launcher: $launcher")
    println("  Wrapper:  $batchWrapper (restart loop with sentinel stop)")
    println("  Startup:  $startupVbs (runs at logon, hidden)")
    println()
    println("To stop the service: echo.> \"${localAppData}\\unidrive\\stop\"")
    println("NOTE: Ensure ${binDir.absolutePath} is on your PATH.")
}

fun deployLinux(
    home: String,
    jarFile: File,
    projectVersion: String,
) {
    val libDir = file("$home/.local/lib/unidrive")
    val binDir = file("$home/.local/bin")
    val systemdDir = file("$home/.config/systemd/user")
    val targetJar = file("$libDir/${jarFile.name}")
    val launcher = file("$binDir/unidrive")
    val serviceFile = file("$systemdDir/unidrive.service")

    libDir.mkdirs()
    binDir.mkdirs()
    systemdDir.mkdirs()

    run("systemctl", "--user", "stop", "unidrive", ignoreExit = true)

    jarFile.copyTo(targetJar, overwrite = true)

    launcher.writeText(
        """
        |#!/usr/bin/env bash
        |exec java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar "$targetJar" "${'$'}@"
        """.trimMargin() + "\n",
    )
    launcher.setExecutable(true)

    serviceFile.writeText(
        """
        |[Unit]
        |Description=UniDrive cloud storage sync daemon
        |After=network-online.target
        |Wants=network-online.target
        |
        |[Service]
        |Type=simple
        |EnvironmentFile=-%h/.config/unidrive/vault-env
        |ExecStart=%h/.local/bin/unidrive sync --watch
        |SuccessExitStatus=143
        |Restart=on-failure
        |RestartSec=30
        |
        |[Install]
        |WantedBy=default.target
        """.trimMargin() + "\n",
    )

    run("systemctl", "--user", "daemon-reload")

    val enabled = run("systemctl", "--user", "is-enabled", "unidrive", ignoreExit = true)
    if (enabled == "enabled") {
        run("systemctl", "--user", "start", "unidrive")
        println("Service restarted.")
    }

    println("Deployed unidrive $projectVersion:")
    println("  JAR:      $targetJar")
    println("  Launcher: $launcher")
    println("  Service:  $serviceFile")
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
    implementation(libs.kotlinx.coroutines.slf4j) // UD-212: MDCContext for profile MDC propagation
    implementation(libs.picocli)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
