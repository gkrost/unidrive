package org.krost.unidrive.sync.ignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour that must hold without a git binary (the oracle covers the long tail). These are the parts of the
 * API contract the oracle cannot see: `explain`, layers, options and parsing details of text git reads from a file.
 */
class IgnoreMatcherTest {
    private fun matcher(
        text: String,
        options: IgnoreOptions = IgnoreOptions(),
    ) = IgnoreMatcher(listOf(IgnoreRules.parse(text, source = ".unidrive.ignore", options = options)))

    @Test
    fun explainNamesTheWinningRuleAndItsLine() {
        val m = matcher("# header\n\n*.log\n!keep.log\n")
        val keep = m.explain("keep.log")
        assertFalse(keep.ignored)
        assertEquals(IgnoreRule(".unidrive.ignore", 4, "!keep.log", negated = true, dirOnly = false), keep.rule)
        val other = m.explain("sub/other.log")
        assertTrue(other.ignored)
        assertEquals(3, other.rule?.line)
        assertEquals("sub/other.log", other.decidedAt)
        assertNull(m.explain("readme.md").rule)
        assertFalse(m.explain("readme.md").ignored)
    }

    @Test
    fun explainReportsTheExcludedAncestorNotTheNegation() {
        val m = matcher("build/\n!build/keep.txt\n")
        val v = m.explain("build/keep.txt")
        assertTrue(v.ignored)
        assertEquals("build/", v.rule?.text)
        assertEquals("build", v.decidedAt)
    }

    @Test
    fun explainEntryLooksAtTheEntryOnly() {
        val m = matcher("build/\n!build/keep.txt\n")
        // A pruning walker never gets here for a file below build/; asked on its own the entry is re-included.
        assertFalse(m.explainEntry("build/keep.txt").ignored)
        assertTrue(m.explainEntry("build", isDir = true).ignored)
        assertFalse(m.explainEntry("build", isDir = false).ignored)
    }

    @Test
    fun aTrailingSlashOnThePathMeansDirectory() {
        val m = matcher("cache/\n")
        assertTrue(m.isIgnored("cache/"))
        assertTrue(m.isIgnored("cache", isDir = true))
        assertFalse(m.isIgnored("cache"))
    }

    @Test
    fun aFileWithoutFinalNewlineAndWithCrlfAndBom() {
        val m = matcher("﻿first\r\nsecond")
        assertTrue(m.isIgnored("first"))
        assertTrue(m.isIgnored("second"))
        assertEquals(listOf("first", "second"), IgnoreRules.parse("﻿first\r\nsecond").rules.map { it.text })
    }

    @Test
    fun trailingSpacesAreTrimmedUnlessEscaped() {
        assertEquals("foo", IgnoreRules.trimTrailingSpaces("foo   "))
        assertEquals("foo\\ ", IgnoreRules.trimTrailingSpaces("foo\\ "))
        assertEquals("foo\\ ", IgnoreRules.trimTrailingSpaces("foo\\   "))
        assertEquals("a b", IgnoreRules.trimTrailingSpaces("a b "))
        assertEquals("foo\\", IgnoreRules.trimTrailingSpaces("foo\\"))
    }

    @Test
    fun asciiCaseFoldingOnlyWhenAsked() {
        assertFalse(matcher("README\n").isIgnored("readme"))
        val ci = IgnoreOptions(ignoreCase = true)
        assertTrue(matcher("README\n", ci).isIgnored("readme"))
        // git folds ASCII only, and so do we.
        assertFalse(matcher("École\n", ci).isIgnored("école"))
    }

    @Test
    fun nfcOptionMakesCompositionFormsEqual() {
        val nfdName = "café"
        val nfcName = "café"
        assertFalse(matcher("$nfcName\n").isIgnored(nfdName))
        assertTrue(matcher("$nfcName\n", IgnoreOptions(normalizeNfc = true)).isIgnored(nfdName))
        assertTrue(matcher("$nfdName\n", IgnoreOptions(normalizeNfc = true)).isIgnored(nfcName))
    }

    @Test
    fun questionMarkIsOneByteLikeGit() {
        // Documented in DEVIATIONS.md: git matches bytes, so a two-byte UTF-8 letter needs two question marks.
        assertFalse(matcher("caf?\n").isIgnored("café"))
        assertTrue(matcher("caf??\n").isIgnored("café"))
    }

    @Test
    fun deeperLayerBeatsShallowerAndAppliesOnlyBelowItsBase() {
        val root = IgnoreRules.parse("foo\n", source = ".unidrive.ignore")
        val sub = IgnoreRules.parse("!foo\n", source = "sub/.unidrive.ignore", base = "sub")
        val m = IgnoreMatcher(listOf(sub, root))
        assertTrue(m.isIgnored("foo"))
        assertFalse(m.isIgnored("sub/foo"))
        assertEquals("sub/.unidrive.ignore", m.explain("sub/x/foo").rule?.source)
        assertTrue(m.isIgnored("other/foo"))
        // The base directory itself is not below its own file.
        assertNull(m.explain("sub", isDir = true).rule)
    }

    @Test
    fun emptyAndRootPathsAreNeverIgnored() {
        val m = matcher("*\n")
        assertFalse(m.isIgnored(""))
        assertFalse(m.isIgnored("/"))
    }

    @Test
    fun noRulesMeansNothingIsIgnored() {
        assertFalse(IgnoreMatcher(emptyList()).isIgnored("anything"))
        assertFalse(matcher("# only a comment\n").isIgnored("anything"))
    }

    @Test
    fun pathologicalStarChainDoesNotBlowUp() {
        val m = matcher("*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b\n")
        val name = "a".repeat(200) + "c"
        val t0 = System.nanoTime()
        assertFalse(m.isIgnored(name))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "a star chain on a non-matching name must fail fast")
    }
}
