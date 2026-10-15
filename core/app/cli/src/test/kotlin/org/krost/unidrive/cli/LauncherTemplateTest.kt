package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The PowerShell launcher template builds the JVM argument list in PowerShell, where `,` binds tighter than `+`:
 * `@('-A=' + $x + 'f', '-B=' + $y)` is ONE argument that carries a space and a trailing backslash. pwsh 7 quotes
 * that, Windows PowerShell 5.1 (the shell the mount scripts start the daemon launcher with) does not, and java
 * prints its usage text instead of starting. This renders the template, lets the real `powershell.exe` build the
 * list and checks that every JVM flag is an argument of its own.
 */
class LauncherTemplateTest {
    private fun templateFile(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("dist/launcher/unidrive.ps1.tmpl")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("dist/launcher/unidrive.ps1.tmpl not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The template's last statement, which decides how the JVM start receives standard input. */
        const val JAVA_LINE = "if ([Console]::IsInputRedirected) { \$input | & java @javaArgs @passArgs } else { & java @javaArgs @passArgs }"
    }

    /** Renders the template, lets the real powershell.exe build the JVM argument list for [passArgs] and returns it. */
    private fun launcherArgs(vararg passArgs: String): List<String> {
        val dir = Files.createTempDirectory("launcher-tmpl")
        try {
            val flags = templateFile().parent.resolve("jvm-flags.txt").toFile().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val rendered = templateFile().toFile().readText().replace("\r\n", "\n")
                .replace("@STATIC_FLAGS_PS@", "\$javaArgs += @(" + flags.joinToString(", ") { "'$it'" } + ")")
                .replace("@JAR@", "unidrive.jar")
                .replace("@COROUTINES_DEBUG_AGENT@", "")
                // print the argument list instead of starting java (the stdin branch is not under test here)
                .replace(JAVA_LINE, "\$javaArgs | ForEach-Object { 'ARG:' + \$_ }")
            val script = dir.resolve("launcher.ps1")
            Files.writeString(script, rendered)
            val process = ProcessBuilder(
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()) + passArgs,
            ).redirectErrorStream(true)
                .also { it.environment()["UNIDRIVE_DIAG_DIR"] = dir.resolve("diag").toString() }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "powershell.exe did not finish")
            return output.lines().filter { it.startsWith("ARG:") }.map { it.removePrefix("ARG:") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Renders the template with the JVM start stubbed to echo what reached it, keeping the stdin branch, and runs
     * it: [stdinLines] are fed through a redirected file, `null` inherits a pipe that is closed at once. The tests
     * read the output only after the bounded [Process.waitFor], so the hang this guards against fails the 60 s
     * bound instead of blocking forever on a pipe nobody closes.
     */
    private fun runLauncherStub(stdinLines: List<String>?): Pair<Path, Process> {
        val dir = Files.createTempDirectory("launcher-tmpl")
        val argEcho = "\$javaArgs | ForEach-Object { 'ARG:' + \$_ }"
        val rendered = templateFile().toFile().readText().replace("\r\n", "\n")
            .replace("@STATIC_FLAGS_PS@", "\$javaArgs += @()")
            .replace("@JAR@", "unidrive.jar")
            .replace("@COROUTINES_DEBUG_AGENT@", "")
            // echo the standard input and the argument list instead of starting java, in both branches
            .replace(
                JAVA_LINE,
                "if ([Console]::IsInputRedirected) { \$input | ForEach-Object { 'STDIN:' + \$_ }; $argEcho } else { $argEcho }",
            )
        val script = dir.resolve("launcher.ps1")
        Files.writeString(script, rendered)
        val process = ProcessBuilder(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
        ).redirectErrorStream(true)
            .also { it.environment()["UNIDRIVE_DIAG_DIR"] = dir.resolve("diag").toString() }
        if (stdinLines == null) {
            process.redirectInput(ProcessBuilder.Redirect.PIPE)
        } else {
            val stdin = dir.resolve("stdin.txt")
            Files.write(stdin, stdinLines)
            process.redirectInput(stdin.toFile())
        }
        val running = process.start()
        if (stdinLines == null) running.outputStream.close()
        return dir to running
    }

    @Test
    fun `the powershell launcher passes every diagnostics flag as an argument of its own`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val args = launcherArgs("daemon", "run", "p")
        val errorFile = args.filter { it.startsWith("-XX:ErrorFile=") }
        val dumpPath = args.filter { it.startsWith("-XX:HeapDumpPath=") }
        assertEquals(1, errorFile.size, "one -XX:ErrorFile argument, got: $args")
        assertEquals(1, dumpPath.size, "one -XX:HeapDumpPath argument, got: $args")
        assertTrue(!errorFile[0].removePrefix("-XX:ErrorFile=").contains("-XX:"), "the ErrorFile argument swallowed another flag: ${errorFile[0]}")
        assertTrue(dumpPath[0].endsWith(java.io.File.separator), "the heap dump path ends with a separator: ${dumpPath[0]}")
        assertTrue(args.any { it.startsWith("-Xlog:gc*:file=") }, "daemon run arms the GC log: $args")
    }

    // `autostart` is a one-word command and normally the LAST argument (`unidrive.ps1 autostart`,
    // `unidrive.ps1 -p work autostart`): a loop bounded by Count - 1 never looks at it.
    @Test
    fun `the powershell launcher arms the GC log for autostart as the last argument and for no one-shot command`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        for (pass in listOf(arrayOf("autostart"), arrayOf("-p", "work", "autostart"), arrayOf("sync", "--watch"))) {
            assertTrue(launcherArgs(*pass).any { it.startsWith("-Xlog:gc*:file=") }, "GC log for ${pass.toList()}")
        }
        for (pass in listOf(arrayOf("status"), arrayOf("sync", "--once"), arrayOf("-p", "work", "ls"))) {
            assertTrue(launcherArgs(*pass).none { it.startsWith("-Xlog:gc*") }, "no GC log for the one-shot ${pass.toList()}")
        }
    }

    // #859: piped-in input is the script's $input; the JVM start must receive it (ignore_rules from a file,
    // auth login --password-stdin) instead of seeing an empty standard input and, for ignore_rules, clearing.
    @Test
    fun `the powershell launcher hands redirected standard input to the jvm`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val (dir, process) = runLauncherStub(listOf("*.tmp", "build/"))
        try {
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            assertTrue(finished, "powershell.exe did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("STDIN:*.tmp" in output, "first stdin line reached the stub: $output")
            assertTrue("STDIN:build/" in output, "second stdin line reached the stub: $output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // No redirected input: the launcher must still run to completion and pass the exit code through.
    @Test
    fun `the powershell launcher starts with an empty standard input`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val (dir, process) = runLauncherStub(null)
        try {
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            assertTrue(finished, "powershell.exe did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), "the stub ran to completion: $output")
            assertTrue(output.lines().any { it.startsWith("ARG:") }, "the argument list was still built: $output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
