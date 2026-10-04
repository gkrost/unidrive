plugins {
    kotlin("jvm") version libs.versions.kotlin.get() apply false
    kotlin("plugin.serialization") version libs.versions.kotlin.get() apply false
    // UD-706: ktlint lint, warn-only. Applied to every Kotlin subproject below.
    alias(libs.plugins.ktlint) apply false
    // Static analysis, report-only (see the detekt block in `subprojects`).
    alias(libs.plugins.detekt) apply false
    // Needed at root for the `jacocoMergedReport` task registered below —
    // JacocoReport requires the jacoco classpath to be resolvable on its owner
    // project.
    jacoco
}

// Pin the JaCoCo tool version explicitly so it does not float with the Gradle
// default. Dependabot regenerates the dependency lockfiles with its own Gradle
// toolchain, which resolved a different default (0.8.13) than the project
// wrapper and produced a strict-lock conflict on every gradle bump PR.
// Pinning here makes the resolved version deterministic regardless of toolchain.
// The version must also keep up with the newest JDK any gate leg runs tests on:
// the runtime-JDK leg executes on the bundled runtime JDK, whose class files
// need an ASM that can read them (an older JaCoCo agent spills
// IllegalClassFormatException stacks into test stderr and breaks coverage).
val jacocoToolVersion = "0.8.15"

configure<JacocoPluginExtension> {
    toolVersion = jacocoToolVersion
}

allprojects {
    group = "org.krost.unidrive"
    // Greenfield monorepo restart baseline. Next planned release: 0.0.1. See docs/CHANGELOG.md.
    version = "0.0.1"

    repositories {
        // Mirrors before mavenCentral — see the long comment in settings.gradle.kts.
        // Gradle never falls through to the next repository on an HTTP error, only on
        // a 404, so Central must be reachable only via mirror misses to keep the
        // shared-CI-runner 429s out of the resolution path.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    // Internxt's socket.io-client drags in engine.io-client, which pins
    // okhttp and org.json transitively. Both transitive pins carried known
    // advisories (okhttp cert-validation; org.json DoS + stack-overflow).
    // engine.io-client declares them with {strictly}, so a plain dependency
    // bump won't override — resolutionStrategy.force is required. Applied
    // across all projects so any module that later pulls these gets the
    // patched versions too; only :app:cli and :providers:internxt actually
    // resolve them today. okio rides along transitively from okhttp and
    // lands above its own advisory floor, so it needs no explicit force.
    configurations.all {
        resolutionStrategy {
            force("com.squareup.okhttp3:okhttp:5.5.0")
            force("org.json:json:20260814")
        }
    }
}

// UD-774: temporary disable. ktlint costs ~20–30 s per `./gradlew build` and
// was the dominant per-iteration cost during the UD-240g/UD-240i sessions on
// 2026-05-02. Flip back to `true` to restore the UD-706 / UD-706b setup.
// Re-enable plan: run `scripts/dev/ktlint-sync.sh` after flip to absorb any
// baseline drift, then `./gradlew build` to confirm green, then close UD-774.
val ktlintEnabled = false

subprojects {
    apply(plugin = "jacoco")
    configure<JacocoPluginExtension> {
        toolVersion = jacocoToolVersion
    }
    // UD-706: ktlint lint + format tasks on every subproject (warn-only).
    if (ktlintEnabled) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            android.set(false)
            // UD-706b: strict — per-project baseline.xml (under <project>/config/ktlint/)
            // freezes the current set of violations so `ktlintCheck` fails only on
            // *new* violations. Gradual-improvement path: delete baseline entries
            // once the underlying file is cleaned up.
            ignoreFailures.set(false)
            reporters {
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
            }
        }
    }

    // detekt: report-only. The existing findings are not yet triaged, so they are
    // printed and written to build/reports/detekt/ on every `check` without failing
    // it. Flipping to failing (or a baseline so only new findings fail) is an owner
    // decision once they are burned down. Config deltas: config/detekt/detekt.yml.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            parallel = true
            ignoreFailures = true
            config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        }
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            reports {
                txt.required.set(true)
                html.required.set(true)
                xml.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
            }
        }
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    afterEvaluate {
        if (plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
                // kotlinx-coroutines-test (runTest, StandardTestDispatcher, advanceUntilIdle, currentTime, ...)
                // is still marked experimental and the tests use it throughout: one opt-in for the test
                // compilation instead of an annotation on every test class (76 warnings per build before).
                target.compilations.named("test") {
                    compileTaskProvider.configure {
                        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
                    }
                }
            }
        }
    }

    tasks.withType<JavaExec>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        finalizedBy(tasks.withType<JacocoReport>())
    }
}

// CI's second gate leg runs the engine and its tests on the bundled runtime
// JDK (`-PunidriveTestJvm=<version>`): bytecode stays at the toolchain level
// pinned above, but the test JVMs become the JDK a packaged runtime image
// ships, so a runtime regression surfaces in `check` instead of in a
// packaged build. Unset — the default — keeps every test JVM on the compile
// toolchain.
providers.gradleProperty("unidriveTestJvm").orNull?.let { jvmVersion ->
    val runtimeJvm = org.gradle.jvm.toolchain.JavaLanguageVersion.of(jvmVersion)
    subprojects {
        // Container projects (no java plugin) have no test tasks and no
        // toolchain service — only query where tests can exist.
        pluginManager.withPlugin("java") {
            val runtimeLauncher =
                the<org.gradle.jvm.toolchain.JavaToolchainService>().launcherFor {
                    languageVersion = runtimeJvm
                }
            tasks.withType<Test>().configureEach {
                javaLauncher.set(runtimeLauncher)
                // JEP 500 (JDK 26+): the first reflective write to a final field prints a three-line warning per
                // test JVM, and a future JDK will refuse it. Many provider tests inject a mock HTTP client
                // into a private val that way (follow-up: give those classes real test seams). Enabling it
                // explicitly keeps the log readable and the intent visible. The option does not exist before 26.
                if (runtimeJvm.asInt() >= 26) {
                    jvmArgs("--enable-final-field-mutation=ALL-UNNAMED")
                }
            }
        }
    }
}

tasks.register<JacocoReport>("jacocoMergedReport") {
    // Only projects that actually define a `test` task (leaf modules, not the
    // `:app` / `:providers` containers). Wrapped in a provider so it is
    // resolved after subprojects finish configuring and register their tasks.
    dependsOn(
        provider {
            subprojects.flatMap { sp ->
                sp.tasks.matching { it.name == "test" }.map { it.path }
            }
        },
    )
    // Lazy provider — subprojects have not applied kotlin-jvm (and thus
    // registered SourceSetContainer) at root-configure time. Providers defer
    // lookup until the task is realized, after subprojects finish configuring.
    val mainSources = provider {
        subprojects.flatMap { sp ->
            sp.extensions.findByType<SourceSetContainer>()?.findByName("main")?.allSource?.srcDirs.orEmpty()
        }
    }
    val mainOutputs = provider {
        subprojects.flatMap { sp ->
            sp.extensions.findByType<SourceSetContainer>()?.findByName("main")?.output?.toList().orEmpty()
        }
    }
    val execData = provider {
        subprojects.flatMap { sp ->
            sp.tasks.withType<Test>().mapNotNull { t ->
                t.extensions.findByType<JacocoTaskExtension>()?.destinationFile
            }
        }
    }
    additionalSourceDirs.setFrom(mainSources)
    sourceDirectories.setFrom(mainSources)
    classDirectories.setFrom(mainOutputs)
    executionData.setFrom(execData)
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoMergedReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoMergedReport"))
    }
}

tasks.register("generateNotice") {
    dependsOn(":app:cli:shadowJar")
    doLast {
        val deps = project(":app:cli").configurations.getByName("runtimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .sortedBy { "${it.group}:${it.name}" }
            .joinToString("\n") { "- ${it.name} ${it.version} — ${it.group}" }

        file("NOTICE").writeText(
            """
            |UniDrive
            |
            |This product includes software developed by third parties:
            |
            |$deps
            """.trimMargin() + "\n"
        )
    }
}

tasks.register("release") {
    dependsOn(subprojects.map { "${it.path}:build" })

    doLast {
        val version = project.version.toString()
        val repoName = "unidrive-cli"
        val ghOrg = "gkrost"
        val srcDir = project.projectDir

        fun run(dir: File, vararg cmd: String, ignoreExit: Boolean = false): String {
            val proc = ProcessBuilder(*cmd)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0 && !ignoreExit) {
                throw GradleException("Command failed (exit $exit): ${cmd.joinToString(" ")}\n$output")
            }
            return output.trim()
        }

        // Gate: clean working tree
        val status = run(srcDir, "git", "status", "--porcelain")
        if (status.isNotEmpty()) {
            throw GradleException("Working tree is not clean.\n$status")
        }

        // Gate: gh authenticated
        run(srcDir, "gh", "auth", "status")

        // Generate NOTICE in source dir
        tasks.getByName("generateNotice").actions.forEach { it.execute(tasks.getByName("generateNotice")) }

        // Create temp dir for the clean release repo
        val tmpDir = File("/tmp/unidrive-release-${System.currentTimeMillis()}")
        tmpDir.mkdirs()

        try {
            // Copy release files (multi-module)
            val releaseFiles = listOf(
                "app", "providers",
                "build.gradle.kts", "settings.gradle.kts",
                "gradle", "gradlew", ".gitignore",
                "NOTICE", "README.md"
            )
            for (f in releaseFiles) {
                val src = file(f)
                if (src.exists()) {
                    val dst = File(tmpDir, f)
                    if (src.isDirectory) src.copyRecursively(dst)
                    else { dst.parentFile.mkdirs(); src.copyTo(dst) }
                }
            }

            // Make gradlew executable
            File(tmpDir, "gradlew").setExecutable(true)

            // Init git repo, commit, tag
            run(tmpDir, "git", "init", "-b", "main")
            run(tmpDir, "git", "add", "-A")
            run(tmpDir, "git", "add", "-f", "NOTICE")
            run(tmpDir, "git", "commit", "-m", "v$version")
            run(tmpDir, "git", "tag", "v$version")

            // Nuke + recreate GitHub remote
            run(tmpDir, "gh", "repo", "delete", "$ghOrg/$repoName", "--yes", ignoreExit = true)
            Thread.sleep(2000) // GitHub needs time to propagate deletion
            try {
                run(tmpDir, "gh", "repo", "create", "$ghOrg/$repoName", "--private")
            } catch (e: GradleException) {
                if ("already exists" in e.message.orEmpty()) {
                    throw GradleException(
                        "GitHub repo still exists after delete. Run:\n" +
                        "  gh auth refresh -s delete_repo\nThen retry."
                    )
                }
                throw e
            }

            // Push
            run(tmpDir, "git", "remote", "add", "origin", "https://github.com/$ghOrg/$repoName.git")
            run(tmpDir, "git", "push", "-u", "origin", "main")
            run(tmpDir, "git", "push", "origin", "v$version")

            println("Released $repoName v$version")
            println("  https://github.com/$ghOrg/$repoName")
            println("  Tag: v$version")
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

// UD-709: resolve the right bash interpreter. On Windows, `PATH` lookup of
// plain `bash` typically hits `C:\Windows\System32\bash.exe`, which is the
// WSL relay and fails without a WSL distribution installed. Prefer Git for
// Windows' `bash.exe`, then MSYS2, then fall through to plain `bash` on
// Linux/macOS.
fun resolveBashExecutable(): String {
    if (!org.gradle.internal.os.OperatingSystem.current().isWindows) return "bash"
    val candidates = listOfNotNull(
        System.getenv("PROGRAMFILES")?.let { "$it/Git/bin/bash.exe" },
        System.getenv("PROGRAMFILES(X86)")?.let { "$it/Git/bin/bash.exe" },
        "C:/Program Files/Git/bin/bash.exe",
        "C:/msys64/usr/bin/bash.exe",
    )
    return candidates.firstOrNull { file(it).exists() }
        ?: error(
            "No usable bash found on Windows. Install Git for Windows (or MSYS2) " +
                "and retry, or run tests/integration-test.sh directly from git-bash.",
        )
}

tasks.register<Exec>("integrationTest") {
    description = "Run integration test suite (requires configured providers and network)"
    group = "verification"
    dependsOn(":app:cli:shadowJar")
    workingDir = projectDir
    commandLine(resolveBashExecutable(), "tests/integration-test.sh")
}

tasks.register<Exec>("integrationTestOffline") {
    description = "Run integration tests without network (subset)"
    group = "verification"
    dependsOn(":app:cli:shadowJar")
    workingDir = projectDir
    commandLine(resolveBashExecutable(), "tests/integration-test.sh", "--skip-network")
}
