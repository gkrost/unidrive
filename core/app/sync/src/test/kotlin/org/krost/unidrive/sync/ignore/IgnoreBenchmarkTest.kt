package org.krost.unidrive.sync.ignore

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 1 M path benchmark of the matcher over a realistic rule set (the "project-gradle-kotlin" shape plus node and
 * IDE rules, 30 rules), on a synthetic project tree.
 *
 * Two runs over the same tree:
 *  - pruned: a walker that asks [IgnoreMatcher.explainEntry] about each entry and does not descend into an ignored
 *    directory (what the local scan will do);
 *  - full: every path of the unpruned tree, ask [IgnoreMatcher.explain] (ancestor walk and all), the worst case.
 *
 * The generator costs time of its own, so a third run with a trivial predicate gives the baseline to subtract. The
 * timings are printed and written to build/reports/ignore-benchmark.txt; the assertions are loose (a regression
 * that makes the matcher ten times slower, not a stopwatch on a shared CI machine).
 */
class IgnoreBenchmarkTest {
    private val rules =
        """
        # build output
        .gradle/
        build/
        !gradle/wrapper/gradle-wrapper.jar
        !**/src/main/**/build/
        !**/src/test/**/build/
        out/
        target/
        dist/
        coverage/
        *.class
        *.log
        *.tsbuildinfo
        npm-debug.log*
        # dependencies
        node_modules/
        **/vendor/bundle/
        # IDE and OS
        .idea/
        *.iml
        .vscode/*
        !.vscode/settings.json
        .DS_Store
        Thumbs.db
        *~
        *.swp
        # secrets and local config
        .env
        .env.*
        !.env.example
        local.properties
        /secrets/
        **/*.pyc
        __pycache__/
        """.trimIndent()

    private val matcher = IgnoreMatcher(listOf(IgnoreRules.parse(rules, source = ".unidrive.ignore")))

    /** Depth-first walk of a synthetic monorepo; [visit] returns false to skip a directory's contents. */
    private class Tree(
        val limit: Int,
        val visit: (String, Boolean) -> Boolean,
    ) {
        var entries = 0
        var pruned = 0

        private fun emit(
            path: String,
            isDir: Boolean,
        ): Boolean {
            entries++
            val descend = visit(path, isDir)
            if (isDir && !descend) pruned++
            return isDir && descend
        }

        private fun files(
            dir: String,
            n: Int,
            ext: String,
        ) {
            for (i in 0 until n) {
                if (entries >= limit) return
                emit("$dir/File$i.$ext", false)
            }
        }

        private fun pkgTree(
            dir: String,
            depth: Int,
        ) {
            files(dir, 12, "kt")
            emit("$dir/Generated.class", false)
            emit("$dir/debug.log", false)
            if (depth == 0) return
            for (i in 0 until 4) {
                if (entries >= limit) return
                val sub = "$dir/pkg$i"
                if (emit(sub, true)) pkgTree(sub, depth - 1)
            }
        }

        private fun hidden(
            dir: String,
            n: Int,
        ) {
            // What a pruned walk never sees: a build or dependency directory is large.
            for (i in 0 until n) {
                if (entries >= limit) return
                emit("$dir/f$i.bin", false)
            }
        }

        fun run() {
            var p = 0
            while (entries < limit) {
                val proj = "services/svc$p"
                p++
                emit(proj, true)
                for (f in listOf("README.md", "build.gradle.kts", ".gitignore", "local.properties", "run.log", ".env", ".env.example")) emit("$proj/$f", false)
                for (d in listOf("src/main/kotlin", "src/test/kotlin")) {
                    val dir = "$proj/$d"
                    emit("$proj/${d.substringBefore('/')}", true)
                    emit("$proj/${d.substringBeforeLast('/')}", true)
                    if (emit(dir, true)) pkgTree(dir, 3)
                }
                if (emit("$proj/build", true)) hidden("$proj/build", 4000)
                if (emit("$proj/node_modules", true)) hidden("$proj/node_modules", 6000)
                if (emit("$proj/.idea", true)) hidden("$proj/.idea", 20)
                if (emit("$proj/docs", true)) files("$proj/docs", 60, "md")
                if (emit("$proj/.vscode", true)) {
                    emit("$proj/.vscode/settings.json", false)
                    emit("$proj/.vscode/launch.json", false)
                }
            }
        }
    }

    @Test
    fun oneMillionPathsOverARealisticRuleSet() {
        assertEquals(30, matcher.let { IgnoreRules.parse(rules).rules.size }, "the rule set the benchmark states")
        val target = 1_000_000

        // Warm up the JIT with a short run of each kind so the numbers are of steady state.
        Tree(100_000) { p, d -> !matcher.explainEntry(p, d).ignored }.run()
        Tree(100_000) { p, d -> matcher.explain(p, d).ignored.let { true } }.run()

        var sink = 0
        val baseTree = Tree(target) { p, _ -> p.length > 0 }
        val tBase = time { baseTree.run() }

        var ignoredPruned = 0
        val prunedTree =
            Tree(target) { p, d ->
                val ignored = matcher.explainEntry(p, d).ignored
                if (ignored) ignoredPruned++
                !ignored
            }
        val tPruned = time { prunedTree.run() }

        var ignoredFull = 0
        val fullTree =
            Tree(target) { p, d ->
                if (matcher.explain(p, d).ignored) ignoredFull++
                true
            }
        val tFull = time { fullTree.run() }
        sink += ignoredPruned + ignoredFull

        val report =
            buildString {
                appendLine("rules: 30, synthetic monorepo, JDK ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus")
                appendLine("generator only (trivial predicate): ${baseTree.entries} paths in $tBase ms")
                appendLine("pruned walk, explainEntry: ${prunedTree.entries} paths evaluated in $tPruned ms; $ignoredPruned ignored entries, ${prunedTree.pruned} directories pruned")
                appendLine("flat list of unpruned tree, explain: ${fullTree.entries} paths in $tFull ms; $ignoredFull ignored")
                appendLine("matcher time (minus generator): pruned ${tPruned - tBase} ms, full ${tFull - tBase} ms")
            }
        println(report)
        val out = Paths.get("build", "reports", "ignore-benchmark.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)

        assertTrue(prunedTree.entries >= target && fullTree.entries >= target)
        assertTrue(prunedTree.pruned > 0 && ignoredPruned > 0, "the tree really has ignored directories to prune")
        assertTrue(tPruned < 30_000 && tFull < 60_000, "1 M paths must take seconds, not minutes: pruned $tPruned ms, full $tFull ms")
        assertTrue(sink >= 0)
    }

    /**
     * The same synthetic tree, now through [IgnoreTree]: the rules are split over a root `.unidrive.ignore` (IDE and
     * OS) and one `.unidrive.ignore` per project (build output, dependencies, secrets), plus the built-in content
     * defaults, and the walk prunes on [IgnoreTree.explainEntry]. Three walks: the first scan (every directory
     * probed, every rule file parsed), a second scan after [IgnoreTree.revalidate] with nothing changed (probes,
     * no parsing), and a third after one project's file changed (exactly that file parsed again).
     */
    @Test
    fun oneMillionPathsThroughDiscoveryWithPruning() {
        val rootRules = rules.lines().filter { it.startsWith(".idea") || it.startsWith("*.iml") || it.startsWith(".vscode") || it.startsWith("!.vscode") || it in setOf(".DS_Store", "Thumbs.db", "*~", "*.swp") }
        val projectRules = rules.lines().filterNot { it in rootRules }
        val files = HashMap<String, String>()
        files[".unidrive.ignore"] = rootRules.joinToString("\n", postfix = "\n")
        for (p in 0 until 2_000) files["services/svc$p/.unidrive.ignore"] = projectRules.joinToString("\n", postfix = "\n")
        val src = MemoryIgnoreSource(files)
        // Uncapped: the walks measure discovery and the cache, not the trim of the cache (IgnoreTreeTest pins that).
        val tree = IgnoreTree(src, IgnoreSettings(maxCachedDirs = Int.MAX_VALUE))
        val target = 1_000_000

        // Warm-up on a separate tree, so the measured first scan is cold for its own caches but not for the JIT.
        val warm = IgnoreTree(MemoryIgnoreSource(files))
        repeat(2) { Tree(200_000) { p, d -> !warm.explainEntry(p, d).ignored }.run() }

        val baseTree = Tree(target) { p, _ -> p.length > 0 }
        val tBase = time { baseTree.run() }
        // The same rules in one flat matcher (no discovery): what the rule count alone costs.
        val flat =
            IgnoreMatcher(
                listOf(
                    IgnoreRules.parse(rules, source = ".unidrive.ignore"),
                    IgnoreRules.parse(IgnoreDefaults.CONTENT.joinToString("\n"), source = IgnoreDefaults.DEFAULT_SOURCE),
                ),
            )
        Tree(200_000) { p, d -> !flat.explainEntry(p, d).ignored }.run()
        val flatTree = Tree(target) { p, d -> !flat.explainEntry(p, d).ignored }
        val tFlat = time { flatTree.run() }

        fun scan(): Pair<Tree, Long> {
            var ignored = 0
            val t =
                Tree(target) { p, d ->
                    val v = tree.explainEntry(p, d)
                    if (v.ignored) ignored++
                    !v.ignored
                }
            val ms = time { t.run() }
            assertTrue(ignored > 0)
            return t to ms
        }

        val (first, tFirst) = scan()
        val readsFirst = src.totalReads
        val probesFirst = src.totalProbes
        tree.revalidate()
        val (_, tSecond) = scan()
        val readsSecond = src.totalReads - readsFirst
        src.put("services/svc0/.unidrive.ignore", projectRules.joinToString("\n", postfix = "\n") + "docs/\n")
        tree.revalidate()
        val (third, tThird) = scan()
        val readsThird = src.totalReads - readsFirst - readsSecond

        val projects = files.keys.count { k -> src.reads.containsKey(k) } - 1
        val report =
            buildString {
                appendLine("discovery: root file ${rootRules.size} rules + one file per project ${projectRules.size} rules + ${IgnoreDefaults.CONTENT.size} content defaults; JDK ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus")
                appendLine("generator only: ${baseTree.entries} paths in $tBase ms")
                appendLine("same rules in one flat matcher, no discovery: ${flatTree.entries} paths, ${flatTree.pruned} directories pruned, $tFlat ms")
                appendLine("first scan (cold caches): ${first.entries} paths, ${first.pruned} directories pruned, $projects projects, $readsFirst rule files parsed, $probesFirst probes, ${tree.cachedDirs} directories cached, $tFirst ms")
                appendLine("second scan (revalidate, nothing changed): $readsSecond rule files parsed, $tSecond ms")
                appendLine("third scan (one project file changed): $readsThird rule files parsed, ${third.pruned} directories pruned, $tThird ms")
                appendLine("matcher + discovery time (minus generator): first ${tFirst - tBase} ms, second ${tSecond - tBase} ms, third ${tThird - tBase} ms")
            }
        println(report)
        val out = Paths.get("build", "reports", "ignore-discovery-benchmark.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)

        assertTrue(first.entries >= target && first.pruned > 0)
        assertEquals(projects + 1, readsFirst, "each rule file parsed once in the first scan")
        assertEquals(0, readsSecond, "nothing parsed again when nothing changed")
        assertEquals(1, readsThird, "only the changed file parsed again")
        assertTrue(third.pruned > first.pruned, "the new docs/ rule prunes more")
        assertTrue(tFirst < 30_000 && tSecond < 30_000, "1 M paths must take seconds: first $tFirst ms, second $tSecond ms")
    }

    private fun time(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }
}
