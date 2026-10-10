package org.krost.unidrive.sync.ignore

import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds the matcher for a corpus case the way a caller would layer real files: deepest directory first. */
internal fun matcherFor(
    files: Map<String, String>,
    options: IgnoreOptions,
): IgnoreMatcher {
    val layers =
        files.entries
            .map { (path, text) ->
                val base = path.substringBeforeLast('/', "")
                IgnoreRules.parse(text, source = path, base = base, options = options)
            }.sortedByDescending { if (it.base.isEmpty()) 0 else it.base.count { c -> c == '/' } + 1 }
    return IgnoreMatcher(layers)
}

internal fun describe(v: IgnoreVerdict): String = v.rule?.let { "${it.source}:${it.line}:${it.text}" } ?: "-"

internal fun describe(a: GitAnswer): String = if (a.matched) "${a.source}:${a.line}:${a.pattern}" else "-"

/**
 * The conformance oracle: every corpus case is asked of `git check-ignore` and of [IgnoreMatcher], and the winning
 * rule (file, line, text) must be identical for every path, with `core.ignorecase` off and on.
 *
 * With [IgnoreOptions.normalizeNfc] the matcher is checked against git run on the NFC-normalised inputs: the option
 * is a deliberate deviation from git's byte semantics, but a precisely defined one.
 */
class IgnoreOracleTest {
    @Test
    fun corpusAgreesWithGit() {
        val located = GitOracle.locate()
        if (located.oracle == null) println("SKIPPED ignore oracle: ${located.skipReason}")
        Assume.assumeTrue(located.skipReason, located.oracle != null)
        val oracle = located.oracle!!
        println("ignore oracle: ${oracle.versionText}")

        val diffs = ArrayList<String>()
        val harnessErrors = ArrayList<String>()
        var cases = 0
        var runs = 0
        var verdicts = 0
        var nfcRuns = 0
        val skipped = ArrayList<String>()
        var unaskable = 0
        for (rawCase in IgnoreCorpus.load()) {
            val askable = rawCase.paths.filter { oracle.canAsk(it) }
            unaskable += rawCase.paths.size - askable.size
            val case = rawCase.copy(paths = askable)
            if (case.minGit != null && !oracle.atLeast(case.minGit)) {
                skipped.add("${case.name} (needs git ${case.minGit.joinToString(".")})")
                continue
            }
            cases++
            for (ignoreCase in case.modes) {
                runs++
                val answers = ask(oracle, case, case.files, case.paths, ignoreCase, harnessErrors) ?: continue
                verdicts += answers.size
                val matcher = matcherFor(case.files, IgnoreOptions(ignoreCase = ignoreCase))
                for (a in answers) {
                    val ours = matcher.explain(a.path)
                    val expectIgnored = a.matched && !a.pattern.startsWith("!")
                    if (describe(ours) != describe(a) || ours.ignored != expectIgnored) {
                        diffs.add("${case.resource} / ${case.name} / ignorecase=$ignoreCase / '${show(a.path)}': git=${describe(a)} ours=${describe(ours)}")
                    }
                }

                // NFC option: same answers as git has for the NFC-normalised rules and paths.
                val nfcFiles = case.files.mapValues { IgnoreCorpus.nfc(it.value) }
                val nfcPaths = case.paths.map { IgnoreCorpus.nfc(it) }
                val changes = nfcFiles != case.files || nfcPaths != case.paths
                val reference = if (changes) (ask(oracle, case, nfcFiles, nfcPaths, ignoreCase, harnessErrors) ?: continue).also { nfcRuns++ } else answers
                val nfcMatcher = matcherFor(case.files, IgnoreOptions(ignoreCase = ignoreCase, normalizeNfc = true))
                for ((i, a) in reference.withIndex()) {
                    val ours = nfcMatcher.explain(case.paths[i])
                    if (describe(ours) != describe(a)) {
                        diffs.add("${case.resource} / ${case.name} / ignorecase=$ignoreCase / nfc / '${show(case.paths[i])}': git(NFC inputs)=${describe(a)} ours=${describe(ours)}")
                    }
                }
            }
        }
        val summary =
            "git ${oracle.versionText}: $cases cases, $runs case x ignorecase runs ($nfcRuns extra NFC runs), " +
                "$verdicts path verdicts compared ($unaskable not askable of this git on this OS), ${diffs.size} differences, ${skipped.size} cases skipped for git version"
        println(summary)
        skipped.forEach { println("  skipped: $it") }
        val report = Paths.get("build", "reports", "ignore-oracle.txt")
        Files.createDirectories(report.parent)
        Files.writeString(report, summary + "\n" + diffs.joinToString("\n") + "\n")
        assertEquals(emptyList<String>(), harnessErrors, "harness errors")
        assertEquals(emptyList<String>(), diffs, summary)
        assertTrue(verdicts > 0)
    }

    private fun show(s: String): String = s.map { if (it.code in 0x20..0x7e) it.toString() else "\\u%04x".format(it.code) }.joinToString("")
}

private fun ask(
    oracle: GitOracle,
    case: OracleCase,
    files: Map<String, String>,
    paths: List<String>,
    ignoreCase: Boolean,
    errors: MutableList<String>,
): List<GitAnswer>? =
    try {
        oracle.check(files, paths, ignoreCase)
    } catch (e: IllegalStateException) {
        errors.add("${case.resource} / ${case.name} / ignorecase=$ignoreCase: ${e.message}")
        null
    }
