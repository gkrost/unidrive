package org.krost.unidrive.sync.ignore

import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovery, layering and caching of [IgnoreTree]. The precedence these tests pin is written down in
 * docs/dev/specs/ignore-layering-design.md; the git-conformance part (nested files) is in [IgnoreOracleTest].
 */
class IgnoreTreeTest {
    private fun tree(
        vararg files: Pair<String, String>,
        settings: IgnoreSettings = IgnoreSettings(),
        problems: MutableList<IgnoreProblem>? = null,
    ): Pair<IgnoreTree, MemoryIgnoreSource> {
        val src = MemoryIgnoreSource(files.toMap())
        return IgnoreTree(src, settings) { problems?.add(it) } to src
    }

    private fun IgnoreExplanation.where(): String = rule?.let { "${it.source}:${it.line}:${it.text}" } ?: "-"

    // ---- the built-in defaults --------------------------------------------------------------------------------

    @Test
    fun gitIsExcludedByDefaultAndExplainSaysDefault() {
        val (t, _) = tree()
        val dir = t.explain(".git", isDir = true)
        assertTrue(dir.ignored)
        assertEquals(IgnoreLayer.DEFAULT, dir.layer)
        assertEquals(IgnoreDefaults.DEFAULT_SOURCE, dir.rule?.source)
        assertEquals(".git", dir.rule?.text)
        val inside = t.explain("proj/.git/objects/ab/cdef")
        assertTrue(inside.ignored)
        assertEquals("proj/.git", inside.decidedAt)
        assertEquals(IgnoreLayer.DEFAULT, inside.layer)
        // A worktree's or submodule's `.git` file is excluded too, as by the old `**/.git/**` default.
        assertTrue(t.isIgnored("proj/sub/.git"))
        assertFalse(t.isIgnored(".gitignore"))
        assertFalse(t.isIgnored("proj/.github/workflows/build.yml"))
    }

    @Test
    fun aRootRuleReIncludesGitEverywhere() {
        for (rule in listOf("!.git", "!.git/", "!**/.git")) {
            val (t, _) = tree(".unidrive.ignore" to "$rule\n")
            assertFalse(t.isIgnored(".git", isDir = true), rule)
            assertFalse(t.isIgnored("a/b/.git/HEAD"), rule)
            assertEquals(IgnoreLayer.UNIDRIVE_IGNORE, t.explain("a/.git", isDir = true).layer, rule)
        }
    }

    @Test
    fun aNestedRuleReIncludesGitForThatRepositoryOnly() {
        val (t, _) = tree("work/proj/.unidrive.ignore" to "!.git\n")
        assertFalse(t.isIgnored("work/proj/.git/config"))
        assertFalse(t.isIgnored("work/proj/lib/.git/config"), "a nested repository below the rule file too")
        assertTrue(t.isIgnored("work/other/.git/config"))
        assertTrue(t.isIgnored("work/.git/config"), "the rule file applies below its own directory only")
    }

    @Test
    fun aRuleForAFileInsideGitCannotReIncludeIt() {
        // git's re-include limit: `.git` stays excluded, so nothing below it comes back.
        val (t, _) = tree(".unidrive.ignore" to "!.git/config\n!.git/**\n")
        val v = t.explain("p/.git/config")
        assertTrue(v.ignored)
        assertEquals(IgnoreLayer.DEFAULT, v.layer)
        assertEquals("p/.git", v.decidedAt)
    }

    @Test
    fun defaultsCanBeReplacedAndEmptied() {
        val (t, _) = tree(settings = IgnoreSettings(contentDefaults = emptyList()))
        assertFalse(t.isIgnored(".git/HEAD"))
        assertFalse(t.isIgnored("a.tmp"))
    }

    @Test
    fun theContentDefaultsMatchTheOldDefaultExcludesUnderAWalk() {
        // The old default list, matched by Reconciler.matchesGlob, as a pruning walk applies it: a path is out when
        // it or one of its ancestor directories matches.
        val oldContent = SyncConfig.DEFAULT_EXCLUDE_PATTERNS.filterNot { it.startsWith("/.unidrive-") }
        val paths =
            listOf(
                ".git", "a/.git", "a/.git/objects/x", ".gitignore", "a/.gitx", "x.git", "a/.github/x",
                ".svn/entries", "a/.hg/store", "b/.bzr", "x.svn", ".directory.lock", "a/.directory.lock",
                ".DS_Store", "a/.DS_Store", "a/._foo", "a/b._foo", "._", "Thumbs.db", "a/ehthumbs.db",
                "a/desktop.ini", "a/desktop.ini.bak", "~\$doc.docx", "a/~\$x", "a~b", "a~", "a/b~", "a.tmp",
                "a.tmp/x", "a/b.tmp", "tmp", "a.temp", "x.part", "x.part2", "a/x.crdownload", "a.swp", "a.swx",
                "a.swo", "doc.txt", "a/b/c.kt", "~", "a/~/b",
            )
        val (t, _) = tree()
        val diffs =
            paths.filter { p ->
                val prefixes = p.split('/').runningReduce { acc, s -> "$acc/$s" }
                val old = prefixes.any { pre -> oldContent.any { Reconciler.matchesGlob(pre, it) } }
                old != t.isIgnored(p)
            }
        assertEquals(emptyList(), diffs)
    }

    @Test
    fun operationalRulesCannotBeOverridden() {
        val (t, _) = tree(".unidrive.ignore" to "!.unidrive-trash\n!.unidrive-trash/**\n!/.unidrive-versions/\n")
        for (p in listOf(".unidrive-trash", ".unidrive-trash/a/b.txt", ".unidrive-versions/x")) {
            val v = t.explain(p)
            assertTrue(v.ignored, p)
            assertEquals(IgnoreLayer.OPERATIONAL, v.layer, p)
        }
        // Operational rules are anchored at the sync root; the pre-filter relies on their common prefix.
        assertTrue(IgnoreDefaults.OPERATIONAL.all { it.startsWith("/" + IgnoreDefaults.OPERATIONAL_PREFIX) })
        assertFalse(t.isIgnored("sub/.unidrive-trash/x"))
        assertFalse(t.isIgnored(".UNIDRIVE-TRASH/x"), "case-sensitive unless the account folds case")
        val (folding, _) = tree(settings = IgnoreSettings(options = IgnoreOptions(ignoreCase = true)))
        assertEquals(IgnoreLayer.OPERATIONAL, folding.explain(".UNIDRIVE-TRASH/x").layer)
        assertTrue(t.explainEntry(".unidrive-trash", isDir = true).ignored)
    }

    // ---- layering ----------------------------------------------------------------------------------------------

    @Test
    fun accountRulesBeatDefaultsAndRuleFilesBeatAccountRules() {
        val settings = IgnoreSettings(accountRules = listOf("*.log", "!*.tmp"))
        val (t, _) = tree(".unidrive.ignore" to "!keep.log\n", "sub/.unidrive.ignore" to "*.md\n", settings = settings)
        assertFalse(t.isIgnored("a.tmp"), "an account rule re-includes a default")
        assertEquals(IgnoreLayer.ACCOUNT, t.explain("a.tmp").layer)
        assertTrue(t.isIgnored("x/a.log"))
        assertEquals(IgnoreRule(IgnoreDefaults.ACCOUNT_SOURCE, 1, "*.log", false, false), t.explain("x/a.log").rule)
        assertFalse(t.isIgnored("x/keep.log"), "a rule file beats an account rule")
        assertEquals(".unidrive.ignore:1:!keep.log", t.explain("x/keep.log").where())
        assertEquals("sub/.unidrive.ignore:1:*.md", t.explain("sub/a/README.md").where())
        assertFalse(t.isIgnored("README.md"))
    }

    @Test
    fun theDeepestFileWins() {
        val (t, _) =
            tree(
                ".unidrive.ignore" to "*.a\n",
                "x/.unidrive.ignore" to "!*.a\n*.b\n",
                "x/y/.unidrive.ignore" to "*.a\n!*.b\n",
            )
        assertEquals(".unidrive.ignore:1:*.a", t.explain("1.a").where())
        assertEquals("x/.unidrive.ignore:1:!*.a", t.explain("x/1.a").where())
        assertFalse(t.isIgnored("x/1.a"))
        assertTrue(t.isIgnored("x/1.b"))
        assertEquals("x/y/.unidrive.ignore:1:*.a", t.explain("x/y/z/1.a").where())
        assertFalse(t.isIgnored("x/y/z/1.b"))
    }

    @Test
    fun aDirectoryExcludedHigherUpCannotBeReIncludedBelow() {
        val (t, src) = tree(".unidrive.ignore" to "build/\n", "build/.unidrive.ignore" to "!*\n")
        val v = t.explain("build/keep/x.txt")
        assertTrue(v.ignored)
        assertEquals("build", v.decidedAt)
        assertEquals(".unidrive.ignore:1:build/", v.where())
        assertNull(src.probes["build/.unidrive.ignore"], "a rule file inside an excluded directory is never read")
    }

    @Test
    fun gitignoreIsReadOnlyWhenTheAccountOptsIn() {
        val files = arrayOf(".gitignore" to "*.log\n", "sub/.gitignore" to "!a.log\n")
        val (off, offSrc) = tree(*files)
        assertFalse(off.isIgnored("sub/x.log"))
        assertTrue(offSrc.probes.keys.none { it.endsWith(".gitignore") }, "not even probed")

        val (on, _) = tree(*files, settings = IgnoreSettings(honorGitignore = true))
        val v = on.explain("x.log")
        assertTrue(v.ignored)
        assertEquals(IgnoreLayer.GITIGNORE, v.layer)
        assertEquals(".gitignore:1:*.log", v.where())
        assertFalse(on.isIgnored("sub/a.log"))
    }

    @Test
    fun inOneDirectoryUnidriveIgnoreBeatsGitignoreButADeeperGitignoreBeatsBoth() {
        val settings = IgnoreSettings(honorGitignore = true)
        val (t, _) =
            tree(
                ".gitignore" to "*.log\n",
                ".unidrive.ignore" to "!keep.log\n",
                "sub/.gitignore" to "keep.log\n",
                settings = settings,
            )
        assertEquals(".unidrive.ignore:1:!keep.log", t.explain("keep.log").where())
        assertEquals("sub/.gitignore:1:keep.log", t.explain("sub/keep.log").where())
        assertTrue(t.isIgnored("sub/keep.log"))
    }

    @Test
    fun explainOfAPathNoRuleMatches() {
        val (t, _) = tree(".unidrive.ignore" to "*.log\n")
        val v = t.explain("src/main.kt")
        assertFalse(v.ignored)
        assertNull(v.rule)
        assertNull(v.layer)
        assertFalse(t.explain("").ignored)
    }

    @Test
    fun explainEntryIsTheWalkersQuestion() {
        val (t, _) = tree(".unidrive.ignore" to "build/\n!build/keep\n")
        assertTrue(t.explainEntry("build", isDir = true).ignored)
        // A pruning walker never asks about build/keep; asked on its own the entry is re-included.
        assertFalse(t.explainEntry("build/keep").ignored)
        assertTrue(t.explain("build/keep").ignored)
    }

    @Test
    fun caseFoldingAndNfcApplyToEveryLayer() {
        val settings = IgnoreSettings(options = IgnoreOptions(ignoreCase = true, normalizeNfc = true), accountRules = listOf("café"))
        val (t, _) = tree(".unidrive.ignore" to "BUILD/\n", settings = settings)
        assertTrue(t.isIgnored("build/x"))
        assertTrue(t.isIgnored("A/.GIT/HEAD"))
        assertTrue(t.isIgnored("café"))
    }

    // ---- caching and invalidation --------------------------------------------------------------------------------

    @Test
    fun eachRuleFileIsParsedOnceAndEachDirectoryProbedOncePerScan() {
        val (t, src) = tree(".unidrive.ignore" to "*.log\n", "a/.unidrive.ignore" to "*.tmp\n")
        repeat(3) {
            for (i in 0 until 50) {
                t.isIgnored("a/b/f$i.log")
                t.explainEntry("a/b/c/f$i.txt")
            }
        }
        assertEquals(mapOf(".unidrive.ignore" to 1, "a/.unidrive.ignore" to 1), src.reads)
        assertEquals(1, src.probes["a/b/.unidrive.ignore"])
        assertEquals(1, src.probes["a/b/c/.unidrive.ignore"])

        t.revalidate()
        t.isIgnored("a/b/c/x")
        assertEquals(2, src.probes["a/b/c/.unidrive.ignore"], "a new scan checks each stamp once")
        assertEquals(2, src.totalReads, "an unchanged file is not read again")
    }

    @Test
    fun aChangedFileIsSeenAtTheNextScanAndPropagatesToSubdirectories() {
        val (t, src) = tree(".unidrive.ignore" to "*.log\n", "a/b/.unidrive.ignore" to "*.tmp\n")
        assertTrue(t.isIgnored("a/b/c/x.log"))
        src.put(".unidrive.ignore", "*.txt\n")
        assertTrue(t.isIgnored("a/b/c/x.log"), "within one scan the rules stay as they were")
        t.revalidate()
        assertFalse(t.isIgnored("a/b/c/x.log"))
        assertTrue(t.isIgnored("a/b/c/x.txt"))
        assertTrue(t.isIgnored("a/b/c/x.tmp"), "the deeper file still applies")
        assertEquals(1, src.reads["a/b/.unidrive.ignore"], "only the changed file is read again")
    }

    @Test
    fun anExplicitInvalidateRereadsAChangeTheStampCannotSee() {
        val (t, src) = tree("a/.unidrive.ignore" to "*.log\n")
        assertTrue(t.isIgnored("a/x.log"))
        // Same size, same modification time: the stamp cannot tell.
        src.put("a/.unidrive.ignore", "*.tmp\n", modified = src.modifiedOf("a/.unidrive.ignore"))
        t.revalidate()
        assertTrue(t.isIgnored("a/x.log"))
        t.invalidate("a")
        assertFalse(t.isIgnored("a/x.log"))
        assertTrue(t.isIgnored("a/x.tmp"))
    }

    @Test
    fun removingARuleFileMakesItsPathsEligibleAgain() {
        val (t, src) = tree(".unidrive.ignore" to "node_modules/\n", "p/.unidrive.ignore" to "*.bin\n")
        assertTrue(t.isIgnored("p/node_modules/x/index.js"))
        assertTrue(t.isIgnored("p/a.bin"))
        src.remove(".unidrive.ignore")
        src.remove("p/.unidrive.ignore")
        t.revalidate()
        assertFalse(t.isIgnored("p/node_modules/x/index.js"))
        assertFalse(t.isIgnored("p/a.bin"))
        // And a new file is picked up the same way.
        src.put("p/.unidrive.ignore", "*.js\n")
        t.revalidate()
        assertTrue(t.isIgnored("p/node_modules/x/index.js"))
    }

    @Test
    fun invalidateAllForgetsEverything() {
        val (t, src) = tree(".unidrive.ignore" to "*.log\n")
        t.isIgnored("a.log")
        t.invalidateAll()
        t.isIgnored("a.log")
        assertEquals(2, src.reads[".unidrive.ignore"])
    }

    // ---- problems --------------------------------------------------------------------------------------------

    @Test
    fun anUnreadableRuleFileHoldsBackItsDirectoryAndIsReportedOnce() {
        val problems = ArrayList<IgnoreProblem>()
        val (t, src) = tree(problems = problems)
        src.fake("secret/.unidrive.ignore", RuleFileProbe.Unreadable("access denied"))
        val v = t.explain("secret/keys.pem")
        assertTrue(v.ignored)
        assertEquals(IgnoreLayer.UNREADABLE, v.layer)
        assertEquals("secret/.unidrive.ignore", v.rule?.source)
        assertEquals(0, v.rule?.line)
        assertFalse(t.isIgnored("secret", isDir = true), "the directory itself stays")
        assertTrue(t.explainEntry("secret/sub", isDir = true).ignored, "a walker prunes everything below it")
        assertFalse(t.isIgnored("other/keys.pem"))
        t.revalidate()
        t.isIgnored("secret/a")
        assertEquals(listOf(IgnoreProblem("secret/.unidrive.ignore", "access denied", heldBack = true)), problems)
    }

    @Test
    fun anOversizedRuleFileHoldsBackItsDirectory() {
        val problems = ArrayList<IgnoreProblem>()
        val (t, _) = tree("big/.unidrive.ignore" to "x".repeat(100) + "\n", settings = IgnoreSettings(maxRuleFileBytes = 64), problems = problems)
        assertTrue(t.isIgnored("big/a.txt"))
        assertEquals(IgnoreLayer.UNREADABLE, t.explain("big/a.txt").layer)
        assertEquals(1, problems.size)
        assertTrue(problems[0].heldBack)
    }

    @Test
    fun aRuleFileThatMustNotBeFollowedIsTreatedAsAbsentAndReportedOnce() {
        val problems = ArrayList<IgnoreProblem>()
        val (t, src) = tree(".unidrive.ignore" to "*.log\n", problems = problems)
        src.fake("link/.unidrive.ignore", RuleFileProbe.NotFollowed("a symbolic link"))
        assertTrue(t.isIgnored("link/a.log"))
        assertFalse(t.isIgnored("link/a.txt"))
        t.revalidate()
        t.isIgnored("link/b.txt")
        assertEquals(listOf(IgnoreProblem("link/.unidrive.ignore", "a symbolic link", heldBack = false)), problems)
        assertNull(src.reads["link/.unidrive.ignore"])
    }

    @Test
    fun aRuleFileThatIsGoneBetweenProbeAndReadCountsAsAbsent() {
        val src =
            object : IgnoreFileSource {
                override fun probe(path: String): RuleFileProbe = if (path == "a/.unidrive.ignore") RuleFileProbe.Present(5, 1) else RuleFileProbe.Absent

                override fun read(
                    path: String,
                    maxBytes: Long,
                ): ByteArray? = throw java.nio.file.NoSuchFileException(path)
            }
        val t = IgnoreTree(src)
        assertFalse(t.isIgnored("a/x"))
    }
}
