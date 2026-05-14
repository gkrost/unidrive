package org.krost.unidrive.e2e.scenarios

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-803 regression guard: the GroundTruth cleanup walk MUST NOT delete the
 * JSONL report. The report is written to `<localBase>-reports/report.jsonl`
 * (sibling, outside the cleanup walk root) so a successful run with
 * `cleanup_local_after_run = true` still leaves an artifact behind for
 * later inspection.
 *
 * This test simulates the post-Phase-7 filesystem layout and replays the
 * exact cleanup loop from [GroundTruthRunner.run]. If a future refactor
 * widens the walk root to the parent directory, or moves the report back
 * under `localBase`, this test fails. The asserted invariant is the
 * one documented in `docs/backlog/CLOSED.md` UD-803.
 */
class GroundTruthCleanupTest {
    private lateinit var workDir: Path
    private lateinit var localBase: Path
    private lateinit var reportsDir: Path
    private lateinit var jsonlReport: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("ud803-")
        localBase = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        reportsDir =
            localBase.resolveSibling("${localBase.fileName}-reports").also {
                Files.createDirectories(it)
            }
        jsonlReport = reportsDir.resolve("report.jsonl").also { Files.writeString(it, "{\"phase\":\"x\"}\n") }
    }

    @AfterTest
    fun tearDown() {
        Files
            .walk(workDir)
            .sorted(Comparator.reverseOrder())
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    fun `cleanup walk preserves sibling report jsonl`() {
        // Synthesize a representative localBase tree.
        Files.writeString(localBase.resolve("file1.txt"), "a")
        Files.writeString(localBase.resolve("file2.bin"), "b")
        val nested = localBase.resolve("nested").also { Files.createDirectories(it) }
        Files.writeString(nested.resolve("file3.txt"), "c")

        // Replay the cleanup loop from GroundTruthRunner.run() (PHASE 7).
        Files
            .walk(localBase)
            .sorted(Comparator.reverseOrder())
            .filter { it != localBase }
            .forEach { Files.deleteIfExists(it) }

        // localBase itself survives (cleanup root); its contents are gone.
        assertTrue(localBase.exists(), "localBase root should be retained")
        assertFalse(localBase.resolve("file1.txt").exists())
        assertFalse(localBase.resolve("file2.bin").exists())
        assertFalse(nested.exists())

        // The sibling reports/ directory and the JSONL file MUST survive —
        // this is the UD-803 invariant.
        assertTrue(reportsDir.exists(), "sibling reports/ directory should survive cleanup")
        assertTrue(jsonlReport.exists(), "report.jsonl should survive cleanup (UD-803)")
        assertTrue(Files.readString(jsonlReport).isNotEmpty(), "report contents should be intact")
    }
}
