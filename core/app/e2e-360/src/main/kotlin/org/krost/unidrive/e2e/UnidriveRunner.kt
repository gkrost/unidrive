package org.krost.unidrive.e2e

import java.nio.file.Path
import java.nio.file.Paths

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

class UnidriveRunner(
    private val jarPath: Path = findJar(),
) {
    companion object {
        fun findJar(): Path {
            // Search relative to CWD and parent (handles running from project root or submodule).
            // The `..` paths are dev-mode candidates for running from inside the module build tree;
            // they're not constructed from user input, so the path-traversal warning doesn't apply.
            val candidates = listOf(
                Paths.get("core/app/cli-full/build/libs"),
                Paths.get("../cli-full/build/libs"), // nosemgrep: path-traversal-literal-dotdot
                Paths.get("../../cli-full/build/libs"), // nosemgrep: path-traversal-literal-dotdot
            )
            for (dir in candidates) {
                if (java.nio.file.Files.isDirectory(dir)) {
                    val jars = java.nio.file.Files.list(dir).use { stream ->
                        stream.filter { it.fileName.toString().startsWith("unidrive-") && it.fileName.toString().endsWith(".jar") }
                            .filter { java.nio.file.Files.size(it) > 1_000_000 } // fat JAR > 1MB
                            .toList()
                    }
                    if (jars.isNotEmpty()) return jars.first()
                }
            }
            error("UniDrive fat JAR not found. Run: ./gradlew :app:cli-full:shadowJar")
        }
    }

    fun run(vararg args: String): CliResult {
        val cmd = listOf("java", "--enable-native-access=ALL-UNNAMED", "-jar", jarPath.toString()) + args.toList()
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .apply {
                // Pass through Xtra encryption passphrase if set
                System.getenv("UNIDRIVE_XTRA_PASS")?.let { environment()["UNIDRIVE_XTRA_PASS"] = it }
            }
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit = proc.waitFor()
        return CliResult(exit, stdout, stderr)
    }

    fun sync(provider: String, vararg extraArgs: String): CliResult =
        run("-p", provider, "sync", *extraArgs)

    fun syncDryRun(provider: String): CliResult =
        run("-p", provider, "sync", "--dry-run")

    fun syncReset(provider: String): CliResult =
        run("-p", provider, "sync", "--reset")

    fun status(provider: String): CliResult =
        run("-p", provider, "status")

    fun get(provider: String, path: String): CliResult =
        run("-p", provider, "get", path)
}
