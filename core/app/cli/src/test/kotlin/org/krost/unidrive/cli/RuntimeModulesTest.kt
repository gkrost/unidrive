package org.krost.unidrive.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the fat jar's JDK-module footprint. The packaged runtime image adds
 * exactly the modules `jdeps` reports here (plus the jdeps-invisible runtime
 * extras listed next to `jdepsModuleSet` in the build script), so a new
 * dependency that changes the module set must be folded into the image on
 * purpose — a drift fails `check` instead of silently breaking packaged
 * builds. The jar path is supplied by the build (system property
 * `unidrive.runtime.test.jar`); the toolchain test JVM always carries a
 * full JDK, so `jdeps` resolves from `java.home`.
 */
class RuntimeModulesTest {
    @Test
    fun `jar module dependencies match the pinned runtime image set`() {
        val jar = File(System.getProperty("unidrive.runtime.test.jar", ""))
        assertTrue(jar.isFile, "fat jar not found at ${jar.absolutePath} (build wiring lost?)")

        val win = System.getProperty("os.name", "").lowercase().contains("win")
        val jdeps = File(System.getProperty("java.home"), "bin/jdeps${if (win) ".exe" else ""}")

        val proc =
            ProcessBuilder(
                jdeps.absolutePath,
                "--print-module-deps",
                "--multi-release",
                "21",
                "--ignore-missing-deps",
                "-q",
                jar.absolutePath,
            ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(0, proc.waitFor(), "jdeps failed:\n$out")

        assertEquals(
            "java.base,java.instrument,java.management,java.naming,java.sql,jdk.unsupported",
            out.lineSequence().last().trim(),
            "the jar's JDK module set changed — update jdepsModuleSet in app/cli/build.gradle.kts " +
                "(and the extra runtime modules if the new code needs them), then re-verify the image boots the jar",
        )
    }
}
