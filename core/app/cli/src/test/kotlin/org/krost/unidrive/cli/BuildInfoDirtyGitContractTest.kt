package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #65 / UD-733: pins the git ground-truth that `generateBuildInfo`'s DIRTY
 * computation depends on.
 *
 * The dirty predicate in `core/app/cli/build.gradle.kts` consumes
 * `git diff HEAD --numstat` and `git ls-files --others --exclude-standard`
 * and classifies each tracked-file diff line as:
 *
 *   - `0\t0\t<path>`  → mode-only change (e.g. `chmod +x`); NOT dirty.
 *   - `N\tM\t<path>`  → content change with N>0 or M>0; dirty.
 *   - `-\t-\t<path>`  → binary change; dirty.
 *   - untracked file  → reported by `ls-files --others`; dirty.
 *
 * The invariant protected here is the part the build CANNOT verify about
 * itself: that git actually emits `0\t0` for a mode-only change (and the
 * other shapes for content/binary/untracked). If a future git version, a
 * `core.fileMode` default flip, or a staging-semantics change altered those
 * shapes, the mode-only-stays-clean behaviour — and with it the bare-semver
 * / uncommitted-build-WARN logic — would silently regress.
 *
 * This test deliberately does NOT re-implement the build's parser: a copy of
 * the classifier would go green even if build.gradle.kts diverged, giving
 * false confidence. It asserts only the raw git output shapes the parser
 * consumes. If this test is removed or loosened, the mode-only-clean
 * invariant loses its only end-to-end check against git's behaviour.
 */
class BuildInfoDirtyGitContractTest {
    private fun git(
        cwd: Path,
        vararg args: String,
    ): String {
        val proc =
            ProcessBuilder(listOf("git", *args))
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) fail("git ${args.joinToString(" ")} failed ($code): $out")
        return out
    }

    private fun initRepo(): Path {
        val dir = Files.createTempDirectory("buildinfo-dirty-git")
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@unidrive.local")
        git(dir, "config", "user.name", "unidrive-test")
        // The whole point of the test is mode-bit handling; force fileMode on
        // so the fixture is deterministic regardless of the host git config.
        git(dir, "config", "core.fileMode", "true")
        // With host-level autocrlf=true (Git for Windows default), `git diff`
        // emits a "LF will be replaced by CRLF" warning on stderr, which
        // redirectErrorStream merges into the parsed numstat output. Disable
        // conversion in the fixture so the shapes stay deterministic.
        git(dir, "config", "core.autocrlf", "false")
        return dir
    }

    private fun numstatLines(dir: Path): List<String> =
        git(dir, "diff", "HEAD", "--numstat")
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()

    @Test
    fun `mode-only chmod of a tracked file surfaces as a zero-zero numstat line`() {
        // POSIX-only: NTFS has no executable bit, so File.setExecutable is a
        // no-op there and git can never observe a mode-only change — the
        // `0\t0` shape this pins is a POSIX-git contract. Mirrors the
        // isWindows guard in LocalScannerTest's visitFileFailed test.
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        org.junit.Assume.assumeFalse("POSIX-only: mode-only changes are unrepresentable on NTFS", isWindows)
        val dir = initRepo()
        val script = dir.resolve("run.sh")
        Files.writeString(script, "echo hi\n")
        script.toFile().setExecutable(false, false)
        git(dir, "add", "run.sh")
        git(dir, "commit", "-qm", "init")

        // Mode-only change: flip the executable bit, leave content identical.
        assertTrue(script.toFile().setExecutable(true, false), "chmod should succeed")

        val lines = numstatLines(dir)
        assertEquals(
            listOf("0\t0\trun.sh"),
            lines,
            "a mode-only change must produce exactly one '0\\t0' numstat line",
        )
        val untracked = git(dir, "ls-files", "--others", "--exclude-standard").trim()
        assertEquals("", untracked, "a mode-only change must not register as untracked")
    }

    @Test
    fun `content change of a tracked file surfaces nonzero numstat counts`() {
        val dir = initRepo()
        val file = dir.resolve("note.txt")
        Files.writeString(file, "alpha\n")
        git(dir, "add", "note.txt")
        git(dir, "commit", "-qm", "init")

        Files.writeString(file, "alpha\nbeta\n")

        val lines = numstatLines(dir)
        assertEquals(1, lines.size, "expected a single changed-file line; was $lines")
        val parts = lines.single().split('\t')
        val added = parts[0].toIntOrNull() ?: 0
        val deleted = parts[1].toIntOrNull() ?: 0
        assertTrue(
            added > 0 || deleted > 0,
            "a content change must report added>0 or deleted>0; line was '${lines.single()}'",
        )
    }

    @Test
    fun `binary change of a tracked file surfaces a dash-dash numstat line`() {
        val dir = initRepo()
        val bin = dir.resolve("blob.bin")
        Files.write(bin, byteArrayOf(0, 1, 2, 3))
        git(dir, "add", "blob.bin")
        git(dir, "commit", "-qm", "init")

        Files.write(bin, byteArrayOf(0, -1, 0, -1))

        val lines = numstatLines(dir)
        assertEquals(
            listOf("-\t-\tblob.bin"),
            lines,
            "a binary change must produce a '-\\t-' numstat line",
        )
    }

    @Test
    fun `untracked file produces no diff line but is reported by ls-files`() {
        val dir = initRepo()
        Files.writeString(dir.resolve("committed.txt"), "x\n")
        git(dir, "add", "committed.txt")
        git(dir, "commit", "-qm", "init")

        Files.writeString(dir.resolve("new-file.txt"), "y\n")

        assertEquals(
            emptyList(),
            numstatLines(dir),
            "an untracked file must not appear in diff-vs-HEAD numstat",
        )
        val untracked = git(dir, "ls-files", "--others", "--exclude-standard").trim()
        assertEquals("new-file.txt", untracked, "an untracked file must be reported by ls-files --others")
    }
}
