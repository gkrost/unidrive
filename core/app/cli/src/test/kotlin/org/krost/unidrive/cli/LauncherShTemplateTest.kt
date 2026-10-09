package org.krost.unidrive.cli

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Linux launcher runs unidrive on the system's JDK and prefers Java 25+ (the JVM runtime spike, #624,
 * measured Java 21 at ~30 % more CPU to a ready daemon). The template is rendered like the deploys render it
 * and run by the real bash against fake JDKs: each fake `java` prints which JDK it is and its arguments.
 */
class LauncherShTemplateTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        assumeTrue("bash launcher: Linux/macOS only", !System.getProperty("os.name").lowercase().contains("win"))
        dir = Files.createTempDirectory("launcher-sh")
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun launcherDir(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val candidate = d.resolve("dist/launcher/unidrive.sh.tmpl")
            if (Files.exists(candidate)) return candidate.parent
            d = d.parent
        }
        error("dist/launcher/unidrive.sh.tmpl not found above ${System.getProperty("user.dir")}")
    }

    /** A fake JDK `<root>/<name>` whose `release` says [version]; returns its bin/java. */
    private fun fakeJdk(root: Path, name: String, version: String): Path {
        val home = root.resolve(name)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"fake\"\nJAVA_VERSION=\"$version\"\n")
        val java = home.resolve("bin/java")
        Files.writeString(java, "#!/bin/sh\necho \"JAVA:$name\"\nfor a in \"\$@\"; do echo \"ARG:\$a\"; done\n")
        java.toFile().setExecutable(true)
        return java
    }

    private data class Run(val exit: Int, val java: String?, val args: List<String>, val stderr: String)

    /** Renders the template the way build.gradle.kts / install.sh do and runs it with `java` on PATH -> [pathJava]. */
    private fun run(pathJava: Path?, vararg passArgs: String, env: Map<String, String> = emptyMap()): Run {
        val flags = launcherDir().resolve("jvm-flags.txt").toFile().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val script = dir.resolve("unidrive")
        Files.writeString(
            script,
            launcherDir().resolve("unidrive.sh.tmpl").toFile().readText()
                .replace("@STATIC_FLAGS_SH@", flags.joinToString(" ") { "\"$it\"" })
                .replace("@JAR@", "/opt/unidrive.jar")
                .replace("@COROUTINES_DEBUG_AGENT@", ""),
        )
        val pathBin = Files.createDirectories(dir.resolve("pathbin"))
        Files.deleteIfExists(pathBin.resolve("java"))
        if (pathJava != null) Files.createSymbolicLink(pathBin.resolve("java"), pathJava)
        val pb = ProcessBuilder(listOf("bash", script.toString()) + passArgs)
        pb.environment().apply {
            put("PATH", "$pathBin:/usr/bin:/bin")
            put("UNIDRIVE_JVM_DIR", dir.resolve("jvm").toString())
            put("UNIDRIVE_DIAG_DIR", dir.resolve("diag").toString())
            remove("UNIDRIVE_JAVA")
            putAll(env)
        }
        val err = dir.resolve("stderr.txt")
        val p = pb.redirectError(err.toFile()).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "launcher did not finish")
        val lines = out.lines()
        return Run(
            p.exitValue(),
            lines.firstOrNull { it.startsWith("JAVA:") }?.removePrefix("JAVA:"),
            lines.filter { it.startsWith("ARG:") }.map { it.removePrefix("ARG:") },
            Files.readString(err),
        )
    }

    @Test
    fun `a Java 21 on PATH is passed over for the newest 25+ JDK in the JVM folder`() {
        val jvm = dir.resolve("jvm")
        val j21 = fakeJdk(jvm, "jdk-21", "21.0.12")
        fakeJdk(jvm, "jdk-25", "25.0.4")
        fakeJdk(jvm, "jdk-27", "27")
        val r = run(j21, "status")
        assertEquals(0, r.exit, r.stderr)
        assertEquals("jdk-27", r.java)
    }

    @Test
    fun `a 25+ java on PATH is used as is`() {
        val jvm = dir.resolve("jvm")
        val j25 = fakeJdk(jvm, "jdk-25", "25.0.4")
        fakeJdk(jvm, "jdk-27", "27")
        assertEquals("jdk-25", run(j25, "status").java)
    }

    @Test
    fun `UNIDRIVE_JAVA wins over every other candidate`() {
        val jvm = dir.resolve("jvm")
        fakeJdk(jvm, "jdk-25", "25.0.4")
        val chosen = fakeJdk(dir.resolve("elsewhere"), "jdk-21-explicit", "21.0.12")
        val r = run(null, "status", env = mapOf("UNIDRIVE_JAVA" to chosen.toString()))
        assertEquals("jdk-21-explicit", r.java)
    }

    @Test
    fun `only a Java 21 runs, and only a long-running command warns about it`() {
        val j21 = fakeJdk(dir.resolve("jvm"), "jdk-21", "21.0.12")
        val oneShot = run(j21, "status")
        assertEquals("jdk-21", oneShot.java)
        assertFalse(oneShot.stderr.contains("Java 25"), "a one-shot command must not warn: ${oneShot.stderr}")
        val daemon = run(j21, "daemon", "run")
        assertEquals("jdk-21", daemon.java)
        assertTrue(daemon.stderr.contains("running on Java 21; Java 25 or newer"), daemon.stderr)
    }

    @Test
    fun `a Java older than 21 is refused with an actionable message`() {
        val j17 = fakeJdk(dir.resolve("jvm"), "jdk-17", "17.0.9")
        val r = run(j17, "status")
        assertEquals(1, r.exit)
        assertEquals(null, r.java, "java must not be started")
        assertTrue(r.stderr.contains("needs Java 21 or newer, found Java 17"), r.stderr)
    }

    // -Xms64m: JDK <= 25 otherwise commit 1/64 of RAM as the initial heap; the spike measured a one-shot
    // sync at ~385 MB RSS without it and ~200 MB with it. If this flag is dropped, that footprint returns.
    @Test
    fun `the launcher passes the small initial heap from jvm-flags txt before the jar`() {
        val j25 = fakeJdk(dir.resolve("jvm"), "jdk-25", "25.0.4")
        val r = run(j25, "status")
        assertTrue("-Xms64m" in r.args, r.args.toString())
        assertTrue(r.args.indexOf("-Xms64m") < r.args.indexOf("-jar"), r.args.toString())
        assertEquals(listOf("/opt/unidrive.jar", "status"), r.args.takeLast(2))
    }
}
