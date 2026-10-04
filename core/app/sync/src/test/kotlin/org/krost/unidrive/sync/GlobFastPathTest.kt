package org.krost.unidrive.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #552: the string tests that stand in for the regex of the common exclude globs must answer exactly what the
// regex answers, for every default pattern and the shapes users write, on awkward paths too.
class GlobFastPathTest {
    private val patterns: List<String> =
        SyncConfig.DEFAULT_EXCLUDE_PATTERNS +
            listOf(
                "*.log", "_INBOX/*.txt", "docs/**", "/docs/**", "a/b/**", "**/node_modules/**", "**/build", "**/*", "*", "**", "**/**",
                "**/*.tar.gz", "**/foo?.txt", "**/[x].txt", "**/(a)", "**/a*b", "**/*a*", "**/a**b", "**/.cache/**", "**/c*/**", "**/?/**",
                "name", "na*me", "n?me", "~$*", "a/**/z", "**/", "/", "tmp/**/", "x y/**", "**/x y", "$" + "recycle.bin/**", "**/.#*",
            )

    private val lineSeparator = Char(0x2028).toString()

    private val awkward: List<String> =
        listOf(
            "", "/", "//", "///", "a", "/a", "a/", "a//b", "//a", "/.git", ".git", "/.git/", "/.git/config", "/a/.git", "/a/.git/x/y", "/a/.github/x",
            "/a/x.git", "/.gitx", "/a/.GIT/x", "/Thumbs.db", "/a/Thumbs.db", "/a/Thumbs.db/b", "/a/thumbs.db", "/.DS_Store", "/a/b/.DS_Store",
            "/._x", "/a/._x", "/a/b._x", "/~\$a", "/a/~\$a", "/a/b~\$", "/x.part", "/a/x.part", "/a/x.part/y", "/a/x.partial", "/_INBOX/foo.txt",
            "/_INBOX/sub/foo.txt", "/other/foo.txt", "/docs", "/docs/", "/docs/a", "/docsx/a", "/a/b", "/a/b/c", "/a/bc", "/node_modules", "/x/node_modules/y",
            "/x/node_modules", "/build", "/x/build", "/x/buildx", "/a.tar.gz", "/d/a.tar.gz", "/foo1.txt", "/d/foo1.txt", "/d/foo.txt", "/[x].txt", "/d/(a)",
            "/ab", "/a/axb", "/xa", "/xax", "/d/xax/e", "/c1/e", "/d/cx/e", "/d/cx", "/q/x/e", "/q/xy/e", "/a/z", "/a/q/z", "/a/q/r/z", "/az", "/x y/f", "/x y",
            "/d/x y", "/.#lock", "/d/.#lock", "/recycle.bin/x", "/RECYCLE.BIN/x", "/tmp", "/tmp/", "/tmp/a/b", "/a\nb/.git/x", "/a/.git\n", "/a" + lineSeparator + "/x.part",
            "/.cache/x", "/p/.cache", "/p/.cache/x/y", "/desktop.ini", "/p/desktop.ini", "/p/Desktop.ini", "/ehthumbs.db", "/p/q.swp", "/p/.swp", "/.unidrive-trash/x",
            "/.unidrive-trash", "/x/.unidrive-trash/y", "/.unidrive-versions/a",
        )

    private fun randomPaths(count: Int): List<String> {
        val random = Random(552)
        val parts = listOf("a", "b", ".git", ".github", "x.part", "Thumbs.db", "~\$q", "._z", "node_modules", "docs", "build", ".cache", "c1", "x y", "", "foo1.txt", "z", "q.tar.gz", ".")
        return List(count) {
            val depth = 1 + random.nextInt(6)
            (0 until depth).joinToString("/", prefix = if (random.nextInt(8) == 0) "" else "/") { parts[random.nextInt(parts.size)] }
        }
    }

    @Test
    fun `the fast paths answer exactly what the regex answers`() {
        var compared = 0
        var matched = 0
        for (path in awkward + randomPaths(30_000)) {
            for (pattern in patterns) {
                val expected = Reconciler.matchesGlobRegex(path, pattern)
                assertEquals(expected, Reconciler.matchesGlob(path, pattern), "path '$path' against '$pattern'")
                compared++
                if (expected) matched++
            }
        }
        assertTrue(matched > 1_000, "the corpus must hit often enough to mean something ($matched of $compared)")
        assertTrue(compared - matched > 1_000, "and miss often enough ($matched of $compared)")
    }

    @Test
    fun `the default excludes still exclude what they are for`() {
        val excluded = listOf("/.git/config", "/a/.git", "/a/b/.svn/x", "/x/.DS_Store", "/y/Thumbs.db", "/z/~\$doc.docx", "/f/g.part", "/.unidrive-trash/x")
        for (path in excluded) {
            assertTrue(SyncConfig.DEFAULT_EXCLUDE_PATTERNS.any { Reconciler.matchesGlob(path, it) }, "$path is excluded")
        }
        for (path in listOf("/a/.github/x", "/docs/readme.md", "/photos/a.jpg")) {
            assertTrue(SyncConfig.DEFAULT_EXCLUDE_PATTERNS.none { Reconciler.matchesGlob(path, it) }, "$path is not excluded")
        }
    }
}
