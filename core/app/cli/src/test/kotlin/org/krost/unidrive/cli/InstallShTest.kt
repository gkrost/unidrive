package org.krost.unidrive.cli

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * dist/install.sh runs against a throwaway HOME with a fake `systemctl` first on PATH, so the real user
 * manager is never touched. The fake reports two active unidrive units and logs every call.
 */
class InstallShTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private lateinit var jar: Path
    private lateinit var systemctlLog: Path

    @BeforeTest
    fun setUp() {
        assumeTrue("install.sh: Linux only", System.getProperty("os.name").lowercase().contains("linux"))
        dir = Files.createTempDirectory("install-sh")
        home = Files.createDirectories(dir.resolve("home"))
        jar = dir.resolve("unidrive-1.jar")
        Files.writeString(jar, "new jar")
        systemctlLog = dir.resolve("systemctl.log")
        val bin = Files.createDirectories(dir.resolve("bin"))
        val fake = bin.resolve("systemctl")
        Files.writeString(
            fake,
            "#!/bin/sh\necho \"\$*\" >> '$systemctlLog'\n" +
                "case \"\$*\" in *list-units*) echo 'unidrive.service loaded active running x'; " +
                "echo 'unidrive-mount.service loaded active running y';; esac\n",
        )
        fake.toFile().setExecutable(true)
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun installScript(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val candidate = d.resolve("dist/install.sh")
            if (Files.exists(candidate)) return candidate
            d = d.parent
        }
        error("dist/install.sh not found above ${System.getProperty("user.dir")}")
    }

    private fun install(): String {
        val pb = ProcessBuilder("bash", installScript().toString(), jar.toString()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        pb.environment()["PATH"] = "${dir.resolve("bin")}:/usr/bin:/bin"
        val p = pb.start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "install.sh timed out")
        assertEquals(0, p.exitValue(), out)
        return out
    }

    private fun inode(p: Path): Any = Files.getAttribute(p, "unix:ino")

    @Test
    fun reinstall_replaces_the_jar_with_a_fresh_inode_and_leaves_a_held_old_file_intact() {
        install()
        val installed = home.resolve(".local/lib/unidrive/unidrive-1.jar")
        val before = inode(installed)
        Files.newInputStream(installed).use { held ->
            Files.writeString(jar, "newer jar")
            install()
            assertNotEquals(before, inode(installed), "the jar must be a fresh inode, not overwritten in place")
            assertEquals("newer jar", Files.readString(installed))
            assertEquals("new jar", held.readAllBytes().decodeToString(), "a holder of the old jar keeps reading it intact")
        }
    }

    @Test
    fun active_unidrive_units_are_stopped_before_the_copy_and_started_again_after() {
        install()
        val calls = Files.readAllLines(systemctlLog)
        val stop = calls.indexOf("--user stop unidrive.service unidrive-mount.service")
        val start = calls.indexOf("--user start unidrive.service unidrive-mount.service")
        assertTrue(stop >= 0, "stop call missing: $calls")
        assertTrue(start > stop, "start must follow stop: $calls")
    }
}
