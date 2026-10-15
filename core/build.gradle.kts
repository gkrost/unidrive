plugins {
    kotlin("jvm") version libs.versions.kotlin.get() apply false
    kotlin("plugin.serialization") version libs.versions.kotlin.get() apply false
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

// Stable and flaky tests (docs/dev/testing-flaky-policy.md): the JUnit 4 category the quarantined tests carry
// (org.krost.unidrive.testing.FlakyTests, in :app:core's test fixtures) and the per-module task that runs only them.
// The wiring is in the "Stable and flaky tests" section below.
val flakyCategory = "org.krost.unidrive.testing.FlakyTests"
val flakyTestTask = "flakyTest"

allprojects {
    group = "org.krost.unidrive"
    // Greenfield monorepo restart baseline. Next planned release: 0.0.1.
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

// detekt runs as a plain process instead of a JavaExec task: Gradle 9 removed
// JavaExec's ignoreExitValue, and the exit code needs reading — 2 means
// findings beyond the module's baseline (a gate failure, with its own
// message), anything else non-zero is a detekt crash. See the detekt block in
// `subprojects`.
abstract class DetektTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Classpath
    abstract val detektClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val config: RegularFileProperty

    // The module's accepted findings (<project>/config/detekt/baseline.xml).
    // Empty when the module has none, so every finding counts as new.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: ConfigurableFileCollection

    // true: (re)write the baseline from the current findings instead of gating.
    @get:Input
    abstract val createBaseline: Property<Boolean>

    @get:Internal
    abstract val baselineFile: RegularFileProperty

    @get:Internal
    abstract val baselineTaskPath: Property<String>

    @get:OutputFile
    abstract val txtReport: RegularFileProperty

    @get:OutputFile
    abstract val htmlReport: RegularFileProperty

    // Pinned by the registering block; detekt's output does not vary with the
    // JVM minor version.
    @get:Internal
    abstract val javaLauncher: Property<org.gradle.jvm.toolchain.JavaLauncher>

    @TaskAction
    fun analyze() {
        val result = execOperations.javaexec {
            classpath(detektClasspath)
            mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
            javaLauncher.set(this@DetektTask.javaLauncher)
            isIgnoreExitValue = true
            args(
                "--config", config.get().asFile.absolutePath,
                "--build-upon-default-config",
                "--parallel",
                "--report", "txt:${txtReport.get().asFile.absolutePath}",
                "--report", "html:${htmlReport.get().asFile.absolutePath}",
                "--input", sources.files.joinToString(",") { it.absolutePath },
            )
            val file = baselineFile.get().asFile
            if (createBaseline.get()) {
                file.parentFile.mkdirs()
                args("--create-baseline", "--baseline", file.absolutePath)
            } else if (!baseline.isEmpty) {
                args("--baseline", file.absolutePath)
            }
        }
        // detekt-cli exit codes: 0 clean, 2 findings, 1 unexpected error, 3 invalid
        // config. Writing a baseline accepts the findings, so 2 is fine there.
        when {
            result.exitValue == 0 -> Unit
            result.exitValue == 2 && createBaseline.get() -> Unit
            result.exitValue == 2 -> throw GradleException(
                "detekt found issues that are not in the baseline; see ${txtReport.get().asFile}. " +
                    "Fix them, or accept them deliberately with `./gradlew ${baselineTaskPath.get()}`.",
            )
            else -> throw GradleException(
                "detekt exited ${result.exitValue} (a detekt crash, not findings); see the task output above",
            )
        }
    }
}

subprojects {
    apply(plugin = "jacoco")
    configure<JacocoPluginExtension> {
        toolVersion = jacocoToolVersion
    }
    // detekt: a ratchet. Each module's existing findings are accepted in
    // <project>/config/detekt/baseline.xml; `check` fails on any finding not in it.
    // Baseline ids are signatures (file name + declaration), not line numbers, so
    // edits elsewhere in a file do not re-surface them. Burning one down: fix it
    // and delete its line. Accepting a new one deliberately: `<project>:detektBaseline`
    // rewrites the module's baseline from the current findings — review that diff.
    // Reports land in build/reports/detekt/. Config deltas: config/detekt/detekt.yml.
    //
    // detekt-cli runs on the compile toolchain JVM instead of through the detekt
    // Gradle plugin: the plugin analyzes in-process on the Gradle daemon's JVM,
    // and detekt 1.x's embedded Kotlin compiler cannot run on a newer daemon —
    // its JavaVersion parser throws on JDK 26+ and on four-part version strings
    // (detekt#8980). The check-runtime-jdk leg runs the daemon on the runtime JDK
    // by design, so the analysis must not care which JDK drives Gradle. Revisit
    // when detekt 2.0 (config-format rewrite, still alpha) is stable and its
    // embedded compiler tracks the JDK train.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val detektCli = configurations.create("detekt") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add("detekt", libs.detekt.cli.get())

        val kotlinSourceDirs = the<SourceSetContainer>().flatMap { sourceSet ->
            (sourceSet.extensions.findByName("kotlin") as? SourceDirectorySet)
                ?.srcDirs
                ?.filter { it.exists() }
                .orEmpty()
        }
        // Resolved on the project — inside the task lambda `the<>()` would bind to
        // the task's own (empty) extension container.
        val toolchainService = the<org.gradle.jvm.toolchain.JavaToolchainService>()

        val baselinePath = layout.projectDirectory.file("config/detekt/baseline.xml")
        val baselineTask = "$path:detektBaseline"
        fun DetektTask.common(reportName: String) {
            detektClasspath.from(detektCli)
            sources.from(kotlinSourceDirs)
            config.set(rootProject.file("config/detekt/detekt.yml"))
            baselineFile.set(baselinePath)
            baselineTaskPath.set(baselineTask)
            txtReport.set(layout.buildDirectory.file("reports/detekt/$reportName.txt"))
            htmlReport.set(layout.buildDirectory.file("reports/detekt/$reportName.html"))
            // The bytecode target is the one JDK every gate leg resolves
            // regardless of which JDK drives the Gradle daemon.
            javaLauncher.set(
                toolchainService.launcherFor {
                    languageVersion = org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)
                },
            )
        }

        val detekt = tasks.register<DetektTask>("detekt") {
            group = "verification"
            description = "Static analysis; fails on findings not in config/detekt/baseline.xml."
            common("detekt")
            baseline.from(files(baselinePath).filter { it.exists() })
            createBaseline.set(false)
        }
        tasks.register<DetektTask>("detektBaseline") {
            group = "verification"
            description = "Rewrites config/detekt/baseline.xml from the current detekt findings."
            common("detekt-baseline")
            createBaseline.set(true)
            outputs.upToDateWhen { false }
        }
        tasks.named("check") { dependsOn(detekt) }
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        // Report-only lint for the few Java sources: no -Werror, the gate stays `check`.
        options.compilerArgs.add("-Xlint:all")
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
        // A failed assertion's message (the expected/actual values) is what tells a timing flake from a
        // real regression; the default format prints only the exception class and line.
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // The advisory flakyTest run (below) is not part of the coverage report, and the report's input is the
        // stable `test` task: finalizing flakyTest with it would run the whole stable suite behind it.
        if (name != flakyTestTask) finalizedBy(tasks.withType<JacocoReport>())
        // #453: redirect the test JVM's home to the build dir. Tests that build a
        // provider config without an explicit path (OneDriveConfig's default
        // tokenPath, the XDG/user-dir resolvers, …) then read and write under
        // build/test-home instead of the developer's real ~/.config/unidrive —
        // a test run must never reset a real profile's delta cursor. The marker
        // property is what the guard test (OneDriveTestHomeRedirectTest) asserts.
        val testHome = layout.buildDirectory.dir("test-home").get().asFile.absolutePath
        doFirst { java.io.File(testHome).mkdirs() }
        // environment() does not unwrap providers (it stringified one into a
        // literal directory name once) — eager values here.
        environment("HOME", testHome)
        systemProperty("user.home", testHome)
        systemProperty("unidrive.test.home", testHome)
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

// ---- Stable and flaky tests (docs/dev/testing-flaky-policy.md) --------------------------------------------------------
//
// A test that fails on CI without a related change is quarantined, not retried and not deleted: it carries
//     @Category(FlakyTests::class)
//     @Flaky(issue = N, reason = "...", firstFailure = "https://github.com/gkrost/unidrive/actions/runs/...")
// (org.krost.unidrive.testing, :app:core's test fixtures). Then:
//   - every Test task except flakyTest (`test`, and with it `check`, and sync-tracking's liveTest* tiers) excludes the
//     category: it runs the stable tests only and fails on a stable failure, as before;
//   - `flakyTest` (one per module, plus a root aggregate) runs ONLY the category, with ignoreFailures, and still writes
//     the normal XML/HTML reports under build/test-results|reports/flakyTest. Nothing in `check` depends on it;
//   - `flakyReport` (finalizer of the aggregate) prints the outcome, appends a table to the GitHub job summary and
//     emits one ::warning:: per failed flaky test;
//   - `checkFlakyQuarantine` (part of every `check`) fails on a quarantined test without a complete @Flaky and lists
//     the quarantine once.
//
// ignoreFailures covers failed assertions and exceptions, which is what flaky tests do. A test JVM that dies, or a run
// that has to be stopped for taking too long, is a failure of the task itself, which ignoreFailures does not cover;
// see flakyTestGuarded.

class QuarantinedTest(
    val module: String,
    val owner: String,
    val test: String,
    val issue: Int,
    val reason: String,
    val firstFailure: String,
    val location: String,
)

class QuarantineScan(val quarantined: List<QuarantinedTest>, val problems: List<String>)

fun sourcesOf(module: Project): Sequence<java.io.File> =
    module.projectDir.resolve("src/test").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

fun mentionsQuarantine(module: Project): Boolean = sourcesOf(module).any { it.readText().contains("FlakyTests") }

// Index of the parenthesis closing the one at `open`, skipping parentheses inside string literals; -1 if unbalanced.
fun closingParenthesis(text: String, open: Int): Int {
    var depth = 0
    var inString = false
    var i = open
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        i++
    }
    return -1
}

fun scanQuarantine(modules: Collection<Project>, rootDir: java.io.File): QuarantineScan {
    val category = Regex("""@Category\s*\(\s*FlakyTests::class\s*\)""")
    val categoryAtEnd = Regex(category.pattern + """\z""")
    val flakyStart = Regex("""@Flaky\s*\(""")
    val declaration = Regex("""\A(?:[a-z]+\s+)*(fun|class)\s+(`[^`]+`|[A-Za-z_]\w*)""")
    val found = mutableListOf<QuarantinedTest>()
    val problems = mutableListOf<String>()
    for (module in modules) {
        for (file in sourcesOf(module)) {
            val text = file.readText()
            if (!text.contains("FlakyTests") && !flakyStart.containsMatchIn(text)) continue
            val rel = file.relativeTo(rootDir).invariantSeparatorsPath
            val lines = text.lines()

            fun lineOf(index: Int) = text.substring(0, index).count { it == '\n' } + 1

            fun at(index: Int) = "$rel:${lineOf(index)}"
            val marks = category.findAll(text).toList()
            // Every use of the category name is an import or the exact marker, so the build sees all of them.
            val stray = Regex("""\bFlakyTests\b""").findAll(text).count { hit ->
                val line = lines[lineOf(hit.range.first) - 1].trimStart()
                val prose = line.startsWith("import ") || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
                !prose && marks.none { hit.range.first in it.range }
            }
            if (stray > 0) problems += "$rel: FlakyTests is used other than as @Category(FlakyTests::class)"
            for (mark in marks) {
                if (!text.substring(mark.range.last + 1).trimStart().startsWith("@Flaky")) {
                    problems += "${at(mark.range.first)}: @Category(FlakyTests::class) needs @Flaky(issue, reason, firstFailure) right after it"
                }
            }
            for (start in flakyStart.findAll(text)) {
                val where = at(start.range.first)
                if (!categoryAtEnd.containsMatchIn(text.substring(0, start.range.first).trimEnd().takeLast(80))) {
                    problems += "$where: @Flaky without @Category(FlakyTests::class) right before it, so the test still runs in the stable build"
                    continue
                }
                val open = start.range.last
                val close = closingParenthesis(text, open)
                if (close < 0) {
                    problems += "$where: unbalanced @Flaky(...)"
                    continue
                }
                val args = text.substring(open + 1, close)
                val issue = Regex("""\bissue\s*=\s*(\d+)""").find(args)?.groupValues?.get(1)?.toInt() ?: 0
                // One string literal, or several joined with `+` when the reason does not fit a line.
                val reasonLiterals = Regex("""\breason\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)""").find(args)?.groupValues?.get(1).orEmpty()
                val reason = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(reasonLiterals).joinToString("") { it.groupValues[1] }
                val firstFailure = Regex("""\bfirstFailure\s*=\s*"(https://github\.com/gkrost/unidrive/[^"\s]+)"""").find(args)?.groupValues?.get(1).orEmpty()
                if (issue <= 0) problems += "$where: @Flaky needs issue = <number of the open issue that tracks the fix>"
                if (reason.isBlank()) problems += "$where: @Flaky needs reason = \"what makes the test non-deterministic\""
                if (firstFailure.isEmpty()) problems += "$where: @Flaky needs firstFailure = \"https://github.com/gkrost/unidrive/...\" (the first CI run that showed it)"
                // Skip the annotations between @Flaky and the declaration it applies to.
                var rest = text.substring(close + 1).trimStart()
                while (rest.startsWith("@")) {
                    val name = Regex("""\A@[\w.:]+""").find(rest)?.value ?: break
                    rest = rest.substring(name.length)
                    if (rest.startsWith("(")) rest = rest.substring(closingParenthesis(rest, 0).coerceAtLeast(0) + 1)
                    rest = rest.trimStart()
                }
                val decl = declaration.find(rest)
                if (decl == null) {
                    problems += "$where: @Flaky is not followed by a fun or class"
                } else if (issue > 0 && reason.isNotBlank() && firstFailure.isNotEmpty()) {
                    val name = decl.groupValues[2].trim('`')
                    // The test class is the last top-level class above the marker (nested helper classes are indented).
                    val enclosing =
                        Regex("""(?m)^(?:(?:public|internal|private|open|abstract|final)\s+)*class\s+(\w+)""")
                            .findAll(text.substring(0, start.range.first)).lastOrNull()?.groupValues?.get(1)
                    val onClass = decl.groupValues[1] == "class"
                    found += QuarantinedTest(
                        module.path,
                        if (onClass) name else enclosing ?: file.nameWithoutExtension,
                        if (onClass) "*" else name,
                        issue,
                        reason,
                        firstFailure,
                        where,
                    )
                }
            }
        }
    }
    return QuarantineScan(found.sortedWith(compareBy({ it.module }, { it.owner }, { it.test })), problems)
}

// ignoreFailures covers failed assertions and exceptions. It does not cover a test JVM that dies, and Gradle's own task
// `timeout` fails the task from outside its actions. A hung or crashing flaky test must not fail a build, so the task's
// actions are wrapped: a crash is logged, remembered for flakyReport and swallowed, and a watchdog interrupts a run
// that outlives flakyTestTimeout (what Gradle's timeout does, minus the failure; the worker JVM is stopped by the
// interrupt). XML/HTML reports are written only when a run ends normally.
val flakyTestTimeout: java.time.Duration =
    java.time.Duration.ofSeconds(providers.gradleProperty("flakyTestTimeoutSeconds").orNull?.toLong() ?: 300L)
val flakyTaskFailures = java.util.concurrent.ConcurrentHashMap<String, String>()

fun Test.flakyTestGuarded() {
    val original = actions.toList()
    setActions(
        original.map { action ->
            Action<Task> {
                val worker = Thread.currentThread()
                var timedOut = false
                val watchdog = java.util.Timer("flakyTest-watchdog", true)
                watchdog.schedule(
                    object : java.util.TimerTask() {
                        override fun run() {
                            timedOut = true
                            worker.interrupt()
                        }
                    },
                    flakyTestTimeout.toMillis(),
                )
                try {
                    action.execute(this)
                } catch (e: org.gradle.api.BuildCancelledException) {
                    throw e
                } catch (e: Exception) {
                    val why =
                        if (timedOut) {
                            "no end after ${flakyTestTimeout.seconds} s, stopped"
                        } else {
                            generateSequence<Throwable>(e) { it.cause }.last().message ?: e.toString()
                        }
                    flakyTaskFailures[path] = why
                    logger.warn("$path did not finish ($why); flaky tests never fail the build.")
                } finally {
                    watchdog.cancel()
                    Thread.interrupted()
                }
            }
        },
    )
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        // The category must load on the test classpath of every module, also of one with no quarantined test: Gradle's
        // category filter fails the whole task on a class it cannot load. testFixtures keeps it out of the shipped jar.
        if (path != ":app:core") {
            dependencies.add("testImplementation", dependencies.testFixtures(dependencies.project(mapOf("path" to ":app:core"))))
        }
        val testSourceSet = the<SourceSetContainer>()["test"]
        tasks.register<Test>(flakyTestTask) {
            group = "verification"
            description = "Runs only the quarantined (flaky) tests. Reports their outcome, never fails the build, not part of check."
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnit { includeCategories(flakyCategory) }
            ignoreFailures = true
            failOnNoDiscoveredTests = false
            // A probe has to run every time (a failure with ignoreFailures still leaves the task "successful" and
            // so up to date).
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("a flaky run must really run") { true }
            // One log line per flaky test, so a run that never ends still shows what it got through.
            testLogging { events("passed", "failed", "skipped") }
            // A module with nothing quarantined starts no test JVM.
            onlyIf { mentionsQuarantine(project) }
            // A run that is stopped writes no reports; the last run's must not stand in for it.
            doFirst {
                project.delete(
                    layout.buildDirectory.dir("test-results/$flakyTestTask"),
                    layout.buildDirectory.dir("reports/tests/$flakyTestTask"),
                )
            }
            flakyTestGuarded()
        }
    }
    afterEvaluate {
        // After the module's own `tasks.test { useJUnit() }` and after the JaCoCo plugin's task extension, so neither
        // can undo what is set here.
        tasks.withType<Test>().configureEach {
            if (name == flakyTestTask) {
                extensions.configure<JacocoTaskExtension> { isEnabled = false }
            } else {
                useJUnit { excludeCategories(flakyCategory) }
            }
        }
    }
}

tasks.register("checkFlakyQuarantine") {
    group = "verification"
    description = "Fails on a quarantined test without a complete @Flaky (issue, reason, first failing run); lists the quarantine."
    doLast {
        val scan = scanQuarantine(subprojects, rootDir)
        val list = scan.quarantined
        logger.lifecycle(
            if (list.isEmpty()) {
                "Quarantined (flaky) tests: none."
            } else {
                "Quarantined (flaky) tests: ${list.size}. They run in flakyTest, not in test or check; each needs an open issue.\n" +
                    list.joinToString("\n") { "  ${it.module}  ${it.owner} > ${it.test}  (#${it.issue})" }
            },
        )
        layout.buildDirectory.file("flaky/quarantine.tsv").get().asFile.apply {
            parentFile.mkdirs()
            writeText(list.joinToString("") { "${it.issue}\t${it.module}\t${it.owner}\t${it.test}\t${it.location}\n" })
        }
        if (scan.problems.isNotEmpty()) {
            throw GradleException(
                "Quarantine markers are incomplete (docs/dev/testing-flaky-policy.md):\n  " + scan.problems.joinToString("\n  "),
            )
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(":checkFlakyQuarantine") }
}

// GitHub workflow command values: %, CR and LF must be escaped, and `:` and `,` as well in a property.
fun String.workflowData() = replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

fun String.workflowProperty() = workflowData().replace(":", "%3A").replace(",", "%2C")

tasks.register("flakyReport") {
    group = "verification"
    description = "Prints the outcome of the last flakyTest run, adds it to the GitHub job summary and annotates failures."
    // The module tasks cannot be depended on (they run only when asked); this orders it behind them.
    mustRunAfter(provider { subprojects.mapNotNull { it.tasks.findByName(flakyTestTask) } })
    doLast {
        class Row(val module: String, val owner: String, val test: String, val result: String, val message: String)
        val quarantined = scanQuarantine(subprojects, rootDir).quarantined
        val rows = mutableListOf<Row>()
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val unfinished = mutableListOf<Pair<String, String>>()
        subprojects.sortedBy { it.path }.forEach { module ->
            // Only a module whose flakyTest really ran in this build: another module's reports may be older.
            val task = module.tasks.findByName(flakyTestTask)
            if (task == null || !task.state.executed || task.state.skipped) return@forEach
            flakyTaskFailures[task.path]?.let { unfinished += module.path to it }
            val results = module.layout.buildDirectory.dir("test-results/$flakyTestTask").get().asFile
            results.listFiles { f -> f.name.startsWith("TEST-") && f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { xml ->
                val cases = builder.parse(xml).documentElement.getElementsByTagName("testcase")
                for (i in 0 until cases.length) {
                    val case = cases.item(i) as org.w3c.dom.Element

                    fun child(tag: String) = case.getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0) as? org.w3c.dom.Element
                    val failure = child("failure") ?: child("error")
                    val result = when {
                        failure != null -> "failed"
                        child("skipped") != null -> "skipped"
                        else -> "passed"
                    }
                    val message = failure?.getAttribute("message").orEmpty().lineSequence().firstOrNull().orEmpty().take(300)
                    rows += Row(module.path, case.getAttribute("classname").substringAfterLast('.'), case.getAttribute("name"), result, message)
                }
            }
        }
        val passed = rows.count { it.result == "passed" }
        val failed = rows.count { it.result == "failed" }
        val skipped = rows.count { it.result == "skipped" }

        fun issueOf(row: Row) = quarantined.firstOrNull { it.owner == row.owner && (it.test == row.test || it.test == "*") }?.issue

        logger.lifecycle("Flaky tests (advisory, they never fail the build): ${rows.size} run, $passed passed, $failed failed, $skipped skipped.")
        rows.filter { it.result != "passed" }.forEach {
            logger.lifecycle("  ${it.result.uppercase()}  ${it.module}  ${it.owner} > ${it.test}  ${it.message}")
        }
        unfinished.forEach { (module, why) -> logger.lifecycle("  NOT FINISHED  $module  $why") }

        rows.filter { it.result == "failed" }.forEach {
            val tracked = issueOf(it)?.let { n -> " Tracked in #$n." }.orEmpty()
            println(
                "::warning title=${"Flaky test failed (does not fail the build)".workflowProperty()}::" +
                    "${it.owner} > ${it.test} (${it.module}): ${it.message}.$tracked".workflowData(),
            )
        }
        unfinished.forEach { (module, why) ->
            println("::warning title=${"Flaky run did not finish".workflowProperty()}::${"$module: $why".workflowData()}")
        }

        System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotBlank() }?.let { summary ->
            val table = StringBuilder()
            table.append("### Flaky tests (advisory: they never fail the build)\n\n")
            table.append("| Run | Passed | Failed | Skipped |\n|---:|---:|---:|---:|\n| ${rows.size} | $passed | $failed | $skipped |\n\n")
            if (rows.isNotEmpty()) {
                table.append("| Result | Test | Module | Issue |\n|---|---|---|---|\n")
                rows.sortedWith(compareBy({ it.result == "passed" }, { it.module }, { it.owner }, { it.test })).forEach {
                    table.append("| ${it.result} | `${it.owner} > ${it.test}` | `${it.module}` | ${issueOf(it)?.let { n -> "#$n" }.orEmpty()} |\n")
                }
                table.append("\n")
            }
            unfinished.forEach { (module, why) -> table.append("`$module` did not finish: ${why.lineSequence().firstOrNull().orEmpty()}\n\n") }
            if (rows.isEmpty() && unfinished.isEmpty()) table.append("No flaky test ran.\n\n")
            java.io.File(summary).appendText(table.toString())
        }
    }
}

tasks.register("flakyTest") {
    group = "verification"
    description = "Runs every module's quarantined tests and reports them. Never fails the build; run it with --continue."
    dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName(flakyTestTask) } })
    finalizedBy("flakyReport")
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
            sp.tasks.withType<Test>().filter { it.name != flakyTestTask }.mapNotNull { t ->
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

// #560 (4A.5): module dependency edges between our own modules, main source
// sets only. A cloud provider implements the SPI in :app:core and must not
// reach into the sync engine, the mount, or the CLI; :app:sync-tracking
// is a pure engine (the `ts` commands live in :app:cli) and must not depend on
// the CLI. :app:engine-core holds the primitives the mirror engine
// and the mount share (#560 U2) and depends only on :app:core, so neither
// orchestration can leak into it. Wired into every module's `check`. Run
// with --info to print the graph.
val mainProjectEdges: () -> Map<String, Set<String>> = {
    val mainConfigs = setOf("api", "implementation", "compileOnly", "runtimeOnly")
    subprojects.associate { sp ->
        sp.path to sp.configurations
            .filter { it.name in mainConfigs }
            .flatMap { c -> c.dependencies.withType<ProjectDependency>().map { it.path } }
            .toSortedSet()
    }
}

// Test-scope project edges (the test and test-fixture source sets), for the one test-scope rule below.
val testProjectEdges: () -> Map<String, Set<String>> = {
    val testConfigs =
        setOf(
            "testImplementation", "testApi", "testCompileOnly", "testRuntimeOnly",
            "testFixturesApi", "testFixturesImplementation", "testFixturesCompileOnly", "testFixturesRuntimeOnly",
        )
    subprojects.associate { sp ->
        sp.path to sp.configurations
            .filter { it.name in testConfigs }
            .flatMap { c -> c.dependencies.withType<ProjectDependency>().map { it.path } }
            .toSortedSet()
    }
}

tasks.register("checkModuleEdges") {
    group = "verification"
    description = "Fails on a forbidden module dependency edge (#560) or a cycle between modules."
    doLast {
        val edges = mainProjectEdges()
        // The mount front-end's tests run on a SyncEngine host (the only MountHost in production), so
        // :app:hydration's tests depend on :app:sync. The reverse test edge made the two test scopes
        // mutually dependent (#560 U3); the mount-operation tests live in :app:hydration since U8.
        val testEdges = testProjectEdges()
        edges.toSortedMap().forEach { (from, to) ->
            if (to.isNotEmpty()) logger.info("module edge: $from -> ${to.joinToString(", ")}")
        }
        val violations = mutableListOf<String>()
        edges.forEach { (from, to) ->
            if (from.startsWith(":providers:")) {
                (to - ":app:core").forEach { violations += "$from -> $it (a provider may depend only on :app:core)" }
            }
            if (from == ":app:sync-tracking" && ":app:cli" in to) {
                violations += "$from -> :app:cli (the tracking engine is CLI-free; the CLI owns the `ts` commands)"
            }
            if (from == ":app:engine-core") {
                (to - ":app:core").forEach {
                    violations += "$from -> $it (the shared engine core may depend only on :app:core; :app:sync and :app:hydration depend on it)"
                }
            }
            // #560 U3: the mirror front-end (SyncEngine, :app:sync) and the mount front-end
            // (MountEngine, :app:hydration) meet only in :app:engine-core and in the CLI that wires them.
            if (from == ":app:hydration" && ":app:sync" in to) {
                violations += "$from -> :app:sync (the mount front-end builds on :app:engine-core, not on the mirror engine)"
            }
            if (from == ":app:sync" && ":app:hydration" in to) {
                violations += "$from -> :app:hydration (the mirror engine does not depend on the mount front-end)"
            }
        }
        if (":app:hydration" in testEdges[":app:sync"].orEmpty()) {
            violations += ":app:sync (test) -> :app:hydration (mount-operation tests belong in :app:hydration, whose tests depend on :app:sync)"
        }
        // Cycle check (depth-first, three colours).
        val state = mutableMapOf<String, Int>()
        fun visit(node: String, trail: List<String>) {
            when (state[node]) {
                1 -> violations += "cycle: ${(trail.dropWhile { it != node } + node).joinToString(" -> ")}"
                2 -> return
                else -> {
                    state[node] = 1
                    edges[node].orEmpty().forEach { visit(it, trail + node) }
                    state[node] = 2
                }
            }
        }
        edges.keys.sorted().forEach { visit(it, emptyList()) }
        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden module dependency edges:\n  " + violations.joinToString("\n  "))
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(":checkModuleEdges") }
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
