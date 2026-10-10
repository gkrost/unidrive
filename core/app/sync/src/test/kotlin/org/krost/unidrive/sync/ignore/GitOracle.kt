package org.krost.unidrive.sync.ignore

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What `git check-ignore -v` said about one path. [source] is empty when no rule matched. */
internal data class GitAnswer(
    val path: String,
    val source: String,
    val line: Int,
    val pattern: String,
) {
    val matched: Boolean get() = source.isNotEmpty()
}

/**
 * `git check-ignore --stdin -z -v -n --no-index` as the reference implementation, run in a throwaway repository.
 *
 * Environment:
 *  - `UNIDRIVE_ORACLE_GIT`: the git executable to use (CI points it at the pinned build); default `git` on PATH.
 *  - `UNIDRIVE_ORACLE_GIT_VERSION`: when set, the run fails unless `git --version` reports exactly this x.y.z.
 *  - `UNIDRIVE_ORACLE_REQUIRED=1`: a missing git is a failure, not a skip.
 *  - on CI (`CI` set) without `UNIDRIVE_ORACLE_GIT` the test skips: the dedicated pinned-git jobs run it.
 */
internal class GitOracle private constructor(
    private val exe: String,
    val versionText: String,
    val version: List<Int>,
    private val work: Path,
) {
    /**
     * Whether this git on this OS can be asked about [path]. Windows git reads a backslash in a pathspec as a
     * separator and a colon as a drive, and a directory cannot be created under a name with `* ? " < > |`; those names are
     * only askable on POSIX (CI runs them there).
     */
    fun canAsk(path: String): Boolean {
        if (File.separatorChar != BACKSLASH) return true
        if (BACKSLASH in path || ':' in path) return false
        return try {
            if (path.endsWith("/")) work.resolve(path.removeSuffix("/"))
            true
        } catch (e: java.nio.file.InvalidPathException) {
            println("not askable on this OS: '$path' (${e.reason})")
            false
        }
    }

    /** True when this git is at least [min] (compared component-wise, missing components count as 0). */
    fun atLeast(min: List<Int>): Boolean {
        for (i in 0 until maxOf(min.size, version.size)) {
            val a = version.getOrElse(i) { 0 }
            val b = min.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    /**
     * Writes [files] into the throwaway work tree and asks git about [paths] with `core.ignorecase` = [ignoreCase].
     *
     * `git check-ignore` takes a trailing "/" literally as part of the name (so `a/` is matched against the rule `a/` plus a star, and its
     * basename is empty), which no directory walk ever produces. So a directory is asked the way a walk meets
     * it: created on disk and named without the slash, so git resolves it as a directory by itself. Files are
     * asked with nothing on disk; a name that exists there anyway is a corpus collision and fails loudly.
     */
    fun check(
        files: Map<String, String>,
        paths: List<String>,
        ignoreCase: Boolean,
    ): List<GitAnswer> {
        val answers = arrayOfNulls<GitAnswer>(paths.size)
        for (asDirs in listOf(false, true)) {
            val idx = paths.indices.filter { paths[it].endsWith("/") == asDirs }
            if (idx.isEmpty()) continue
            clean()
            for ((rel, text) in files) {
                val target = work.resolve(rel)
                Files.createDirectories(target.parent)
                Files.write(target, text.toByteArray(Charsets.UTF_8))
            }
            val asked = idx.map { paths[it].removeSuffix("/") }
            if (asDirs) {
                asked.forEach { Files.createDirectories(work.resolve(it)) }
            } else {
                asked.forEach { check(!exists(it)) { "corpus collision: file path '$it' exists on this file system" } }
            }
            val got = run(asked, ignoreCase)
            for ((k, i) in idx.withIndex()) answers[i] = got[k].copy(path = paths[i])
        }
        return answers.map { it!! }
    }

    /** A name the file system cannot even spell does not exist there. */
    private fun exists(rel: String): Boolean =
        try {
            Files.exists(work.resolve(rel))
        } catch (ignored: java.nio.file.InvalidPathException) {
            false
        }

    private fun run(
        paths: List<String>,
        ignoreCase: Boolean,
    ): List<GitAnswer> {
        val cmd =
            listOf(
                exe,
                "-c", "core.ignorecase=$ignoreCase",
                "-c", "core.excludesFile=",
                "-c", "core.autocrlf=false",
                "-c", "core.precomposeunicode=false",
                "check-ignore", "--stdin", "-z", "-v", "-n", "--no-index",
            )
        val pb = ProcessBuilder(cmd).directory(work.toFile())
        sanitise(pb.environment())
        val proc = pb.start()
        val feeder =
            thread(isDaemon = true) {
                proc.outputStream.use { out -> paths.forEach { out.write(it.toByteArray(Charsets.UTF_8)); out.write(0) } }
            }
        var stdout = ByteArray(0)
        val outReader = thread(isDaemon = true) { stdout = proc.inputStream.readBytes() }
        var stderr = ByteArray(0)
        val errReader = thread(isDaemon = true) { stderr = proc.errorStream.readBytes() }
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            proc.waitFor()
            feeder.join()
            outReader.join()
            errReader.join()
            error("git check-ignore timed out")
        }
        feeder.join()
        outReader.join()
        errReader.join()
        val code = proc.exitValue()
        check(code == 0 || code == 1) { "git check-ignore exited $code: ${String(stderr, Charsets.UTF_8)}" }
        return parse(stdout, paths)
    }

    private fun parse(
        stdout: ByteArray,
        paths: List<String>,
    ): List<GitAnswer> {
        val fields = ArrayList<String>()
        var start = 0
        for (i in stdout.indices) {
            if (stdout[i].toInt() == 0) {
                fields.add(String(stdout, start, i - start, Charsets.UTF_8))
                start = i + 1
            }
        }
        check(fields.size == paths.size * 4) { "expected ${paths.size * 4} fields for ${paths.size} paths, got ${fields.size}: $fields" }
        return paths.indices.map { i ->
            val (source, line, pattern, path) = fields.subList(i * 4, i * 4 + 4)
            check(path == paths[i]) { "git answered for '$path' where '${paths[i]}' was asked (order or normalisation changed)" }
            GitAnswer(path, source, line.toIntOrNull() ?: 0, pattern)
        }
    }

    private fun clean() {
        Files.list(work).use { s ->
            s.filter { it.fileName.toString() != ".git" }.forEach { deleteTree(it) }
        }
    }

    private fun deleteTree(p: Path) {
        if (Files.isDirectory(p)) Files.list(p).use { s -> s.toList().forEach { deleteTree(it) } }
        Files.delete(p)
    }

    companion object {
        private const val BACKSLASH = '\\'

        /** Everything the host might inject: git must see no config, no repository override, no hooks of ours. */
        private fun sanitise(env: MutableMap<String, String>) {
            env.keys.removeIf { it.startsWith("GIT_") }
            val home = Files.createTempDirectory("ignore-oracle-home")
            home.toFile().deleteOnExit()
            env["HOME"] = home.toString()
            env["USERPROFILE"] = home.toString()
            env["XDG_CONFIG_HOME"] = home.toString()
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_CONFIG_GLOBAL"] = if (File.separatorChar == '\\') "NUL" else "/dev/null"
            env["GIT_TERMINAL_PROMPT"] = "0"
        }

        class Located(
            val oracle: GitOracle?,
            val skipReason: String,
        )

        fun locate(): Located {
            val configured = System.getenv("UNIDRIVE_ORACLE_GIT")?.takeIf { it.isNotBlank() }
            val required = System.getenv("UNIDRIVE_ORACLE_REQUIRED") == "1"
            if (configured == null && System.getenv("CI") != null && !required) {
                return Located(null, "CI without UNIDRIVE_ORACLE_GIT: the pinned-git jobs run the git oracle (see DEVIATIONS.md)")
            }
            val exe = configured ?: "git"
            val versionText =
                try {
                    val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
                    var out = ByteArray(0)
                    val reader = thread(isDaemon = true) { out = p.inputStream.readBytes() }
                    if (!p.waitFor(30, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        p.waitFor()
                        reader.join()
                        null
                    } else {
                        reader.join()
                        if (p.exitValue() != 0) null else out.toString(Charsets.UTF_8).trim()
                    }
                } catch (e: java.io.IOException) {
                    null.also { System.err.println("git not runnable ($exe): ${e.message}") }
                }
            if (versionText == null) {
                check(!required) { "UNIDRIVE_ORACLE_REQUIRED=1 but '$exe' is not runnable" }
                return Located(null, "git ('$exe') is not available: the git-conformance oracle is skipped; install git to run it")
            }
            val version =
                Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(versionText)?.destructured?.let { (a, b, c) ->
                    listOf(a.toInt(), b.toInt(), c.ifEmpty { "0" }.toInt())
                } ?: error("cannot parse git version from '$versionText'")
            System.getenv("UNIDRIVE_ORACLE_GIT_VERSION")?.takeIf { it.isNotBlank() }?.let { pinned ->
                check(version.joinToString(".") == pinned) { "oracle pinned to git $pinned but '$exe' reports '$versionText'" }
            }
            val work = Files.createTempDirectory("ignore-oracle-repo")
            work.toFile().deleteOnExit()
            val init = ProcessBuilder(exe, "init", "-q", ".").directory(work.toFile()).redirectErrorStream(true)
            sanitise(init.environment())
            val p = init.start()
            var out = ByteArray(0)
            val reader = thread(isDaemon = true) { out = p.inputStream.readBytes() }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor()
                reader.join()
                error("git init timed out")
            }
            reader.join()
            check(p.exitValue() == 0) { "git init failed: ${out.toString(Charsets.UTF_8)}" }
            return Located(GitOracle(exe, versionText, version, work), "")
        }
    }
}
