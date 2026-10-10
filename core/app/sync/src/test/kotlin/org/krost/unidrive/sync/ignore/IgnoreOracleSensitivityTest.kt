package org.krost.unidrive.sync.ignore

import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the harness itself: a plausible but naive matcher (a glob per line, last match wins, no anchoring rules, no
 * "excluded directory stays excluded") must disagree with git on many corpus paths. If this test ever passes with
 * few differences, the corpus has stopped discriminating and the green oracle run means little.
 */
class IgnoreOracleSensitivityTest {
    private class NaiveRule(
        val negated: Boolean,
        val dirOnly: Boolean,
        val regex: Regex,
    )

    private fun naiveRules(text: String): List<NaiveRule> =
        text
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { raw ->
                var p = raw
                val negated = p.startsWith("!")
                if (negated) p = p.substring(1)
                val dirOnly = p.endsWith("/")
                p = p.trimEnd('/').removePrefix("/")
                val anyDepth = '/' !in p
                val body = StringBuilder()
                var i = 0
                while (i < p.length) {
                    when {
                        p.startsWith("**", i) -> {
                            body.append(".*")
                            i += 2
                        }
                        p[i] == '*' -> {
                            body.append("[^/]*")
                            i++
                        }
                        p[i] == '?' -> {
                            body.append("[^/]")
                            i++
                        }
                        else -> {
                            body.append(Regex.escape(p[i].toString()))
                            i++
                        }
                    }
                }
                NaiveRule(negated, dirOnly, Regex((if (anyDepth) "(.*/)?" else "") + body + "(/.*)?"))
            }

    private fun naiveIgnored(
        rules: List<NaiveRule>,
        path: String,
        isDir: Boolean,
    ): Boolean {
        var ignored = false
        for (r in rules) {
            if (r.dirOnly && !isDir) continue
            if (r.regex.matches(path)) ignored = !r.negated
        }
        return ignored
    }

    @Test
    fun aNaiveMatcherDisagreesWithGitOnManyCorpusPaths() {
        val located = GitOracle.locate()
        if (located.oracle == null) println("SKIPPED ignore oracle sensitivity: ${located.skipReason}")
        Assume.assumeTrue(located.skipReason, located.oracle != null)
        val oracle = located.oracle!!
        var compared = 0
        var different = 0
        for (case in IgnoreCorpus.load().filter { it.files.keys == setOf(".gitignore") && it.name != "very-long-star-chain" }) {
            if (case.minGit != null && !oracle.atLeast(case.minGit)) continue
            val paths = case.paths.filter { oracle.canAsk(it) }
            val rules = naiveRules(case.files.getValue(".gitignore"))
            val answers = oracle.check(case.files, paths, ignoreCase = false)
            for (a in answers) {
                compared++
                val gitIgnored = a.matched && !a.pattern.startsWith("!")
                val isDir = a.path.endsWith("/")
                if (naiveIgnored(rules, a.path.trimEnd('/'), isDir) != gitIgnored) different++
            }
        }
        println("naive matcher vs git: $different of $compared path verdicts differ")
        assertTrue(different >= 100, "the corpus must catch a naive matcher: only $different of $compared differ")
    }
}
