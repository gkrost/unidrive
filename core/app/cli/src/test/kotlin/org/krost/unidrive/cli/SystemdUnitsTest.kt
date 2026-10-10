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
 * The shipped systemd user units under dist/. Install and uninstall run against a throwaway HOME with a
 * fake `systemctl` first on PATH, so the real user manager is never touched.
 */
class SystemdUnitsTest {
    private lateinit var dir: Path
    private lateinit var home: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("systemd-units")
        home = Files.createDirectories(dir.resolve("home"))
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun distDir(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            if (Files.exists(d.resolve("dist/install.sh"))) return d.resolve("dist")
            d = d.parent
        }
        error("dist/install.sh not found above ${System.getProperty("user.dir")}")
    }

    private fun unit(name: String): String = Files.readString(distDir().resolve(name))

    private fun directives(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun profile_template_runs_autostart_for_the_instance_and_treats_sigterm_as_a_clean_stop() {
        val d = directives(unit("unidrive@.service"))
        assertTrue("ExecStart=%h/.local/bin/unidrive -p %i autostart" in d)
        assertTrue(d.any { it.startsWith("SuccessExitStatus=") && "143" in it })
        assertTrue("NoNewPrivileges=yes" in d)
    }

    @Test
    fun single_unit_keeps_running_autostart_for_the_default_profile() {
        val d = directives(unit("unidrive.service"))
        assertTrue("ExecStart=%h/.local/bin/unidrive autostart" in d)
        assertTrue(d.any { it.startsWith("SuccessExitStatus=") && "143" in it })
    }

    @Test
    fun mount_template_clears_a_dead_mount_around_the_run_and_keeps_privileges_for_fusermount() {
        val d = directives(unit("unidrive-mount@.service"))
        assertTrue("ExecStartPre=-/usr/bin/fusermount3 -uz \${UNIDRIVE_MOUNTPOINT}" in d)
        assertTrue("ExecStopPost=-/usr/bin/fusermount3 -uz \${UNIDRIVE_MOUNTPOINT}" in d)
        assertTrue("ExecStart=%h/.local/bin/unidrive -p %i mount \${UNIDRIVE_MOUNTPOINT}" in d)
        assertTrue("SuccessExitStatus=78 143" in d, "a permanent refusal (78) must not restart-loop: $d")
        // The setuid fusermount3 is blocked by NoNewPrivileges, and a private mount namespace hides the mount.
        val sandboxing = listOf("NoNewPrivileges", "RestrictSUIDSGID", "ProtectSystem", "ProtectHome", "PrivateTmp", "PrivateMounts")
        assertFalse(d.any { line -> sandboxing.any { line.startsWith("$it=") } }, "mount unit must not sandbox: $d")
    }

    @Test
    fun install_places_all_units_and_uninstall_removes_them() {
        assumeTrue("install.sh: Linux only", System.getProperty("os.name").lowercase().contains("linux"))
        val bin = Files.createDirectories(dir.resolve("bin"))
        val fake = bin.resolve("systemctl")
        Files.writeString(fake, "#!/bin/sh\nexit 0\n")
        fake.toFile().setExecutable(true)
        val jar = dir.resolve("unidrive-1.jar")
        Files.writeString(jar, "jar")

        run("install.sh", jar.toString())
        val unitDir = home.resolve(".config/systemd/user")
        val names = listOf("unidrive.service", "unidrive@.service", "unidrive-mount@.service")
        names.forEach { assertTrue(Files.exists(unitDir.resolve(it)), "$it installed") }

        run("uninstall.sh")
        names.forEach { assertFalse(Files.exists(unitDir.resolve(it)), "$it removed") }
    }

    private fun run(script: String, vararg args: String) {
        val pb = ProcessBuilder(listOf("bash", distDir().resolve(script).toString()) + args).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        pb.environment()["PATH"] = "${dir.resolve("bin")}:/usr/bin:/bin"
        val p = pb.start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "$script timed out")
        assertEquals(0, p.exitValue(), out)
    }
}
