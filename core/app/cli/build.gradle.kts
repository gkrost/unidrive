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

        doLast {
            val commit =
                try {
                    gitCommit.get().ifEmpty { "dev" }
                } catch (_: Exception) {
                    "dev"
                }
            val dir = outputDir.get().asFile.resolve("org/krost/unidrive/cli")
            dir.mkdirs()
            dir.resolve("BuildInfo.kt").writeText(
                """
            |package org.krost.unidrive.cli
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
    val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
    val libDir = file("$localAppData\\unidrive")
    val binDir = file("$home\\.local\\bin")
    val targetJar = file("$libDir\\${jarFile.name}")
    val launcher = file("$binDir\\unidrive.cmd")
    val startupDir = file("$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Startup")
    val startupVbs = file("$startupDir\\unidrive.vbs")

    libDir.mkdirs()
    binDir.mkdirs()

    // UD-712: Kill any running unidrive java process before overwriting the
    // JAR. The old taskkill filter only matched `WINDOWTITLE eq UniDriveSync`,
    // which misses background daemons started by the VBS/startup wrapper —
    // those have no window title, so the overwrite raced the open file
    // handle and failed with FileAlreadyExistsException. Match by command
    // line via CIM instead: any java.exe whose cmdline references the CLI
    // shadow jar (`unidrive-<version>.jar`). The two negative lookaheads
    // exclude sibling jars — `unidrive-mcp-*.jar` is killed by
    // `:app:mcp:deploy`. The `unidrive-ui.jar` clause was retired with
    // the `ui/` tier in ADR-0013.
    run(
        "powershell",
        "-NoProfile",
        "-Command",
        // Filter uses PowerShell escaped single-quotes: Windows
        // ProcessBuilder strips embedded double-quotes from command-line
        // args, which breaks `-Filter "Name='java.exe'"`. The doubled
        // single-quote (`''`) is PowerShell's in-string escape for `'`.
        "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | " +
            "Where-Object { \$_.CommandLine -match 'unidrive-(?!mcp-)(?!ui)[^ ]*\\.jar' } | " +
            "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }",
        ignoreExit = true,
    )

    jarFile.copyTo(targetJar, overwrite = true)

    launcher.writeText(
        """
        |@echo off
        |java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar "$targetJar" %*
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
