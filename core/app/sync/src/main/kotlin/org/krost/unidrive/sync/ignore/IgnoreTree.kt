package org.krost.unidrive.sync.ignore

import java.io.IOException
import java.nio.file.NoSuchFileException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The built-in rules, in git syntax. */
object IgnoreDefaults {
    const val OPERATIONAL_SOURCE = "operational"
    const val DEFAULT_SOURCE = "default"
    const val ACCOUNT_SOURCE = "account"

    /**
     * The engine's own folders at the sync root. Checked before everything else: no rule can re-include them. Every
     * one starts with [OPERATIONAL_PREFIX], so other paths skip the check.
     */
    val OPERATIONAL: List<String> = listOf("/.unidrive-trash", "/.unidrive-versions")

    internal const val OPERATIONAL_PREFIX = ".unidrive-"

    /**
     * Content defaults, the lowest layer: any rule of the account or of a rule file overrides them (`!.git`).
     * Version-control metadata (directories, and the `.git` file of a worktree or submodule), then desktop, OS and
     * editor junk. The same set as the old default excludes, re-expressed in git syntax.
     */
    val CONTENT: List<String> =
        listOf(
            ".git", ".svn", ".hg", ".bzr",
            ".directory.lock", ".DS_Store", "._*", "Thumbs.db", "ehthumbs.db", "desktop.ini",
            "~\$*", "*.part", "*.crdownload", "*.swp", "*.swx", "*.tmp", "*~",
        )
}

/**
 * How the rules of one sync root are put together.
 *
 * @property honorGitignore also read `.gitignore` files (per account, off by default).
 * @property accountRules the account's own rules, git syntax, one per entry, applied at the sync root.
 * @property contentDefaults the built-in content defaults ([IgnoreDefaults.CONTENT]).
 * @property options case folding and NFC, for every layer.
 * @property maxRuleFileBytes a larger rule file is not read; everything below its directory is held back.
 */
data class IgnoreSettings(
    val honorGitignore: Boolean = false,
    val accountRules: List<String> = emptyList(),
    val contentDefaults: List<String> = IgnoreDefaults.CONTENT,
    val options: IgnoreOptions = IgnoreOptions(),
    val maxRuleFileBytes: Long = 1024L * 1024L,
)

/** Where the deciding rule came from, lowest precedence last. */
enum class IgnoreLayer { OPERATIONAL, UNREADABLE, UNIDRIVE_IGNORE, GITIGNORE, ACCOUNT, DEFAULT }

/**
 * The answer for one path.
 *
 * @property rule the deciding rule; its [IgnoreRule.source] is the rule file's path relative to the sync root, or
 *   [IgnoreDefaults.DEFAULT_SOURCE], [IgnoreDefaults.ACCOUNT_SOURCE], [IgnoreDefaults.OPERATIONAL_SOURCE]. Null
 *   when no rule matched. For an unreadable rule file it is that file with line 0.
 * @property layer the layer of [rule], null with it.
 * @property decidedAt the path the rule matched: the queried path, or an excluded ancestor directory.
 */
data class IgnoreExplanation(
    val ignored: Boolean,
    val rule: IgnoreRule?,
    val layer: IgnoreLayer?,
    val decidedAt: String,
)

/**
 * A rule file that could not be used. Reported once per state of the file (again only after it changed).
 *
 * @property heldBack true: everything below its directory is ignored until it is readable (fail closed); false:
 *   it is treated as absent (a symbolic link, as git does).
 */
data class IgnoreProblem(
    val file: String,
    val reason: String,
    val heldBack: Boolean,
)

/**
 * The ignore rules of one sync root: discovery, layering and caching over [IgnoreMatcher].
 *
 * Precedence, highest first (docs/dev/specs/ignore-layering-design.md):
 *  1. operational rules ([IgnoreDefaults.OPERATIONAL]), never overridable;
 *  2. rule files, the deepest directory first; in one directory `.unidrive.ignore` before `.gitignore`;
 *  3. the account's rules ([IgnoreSettings.accountRules]);
 *  4. the content defaults ([IgnoreSettings.contentDefaults]).
 *
 * Within a layer the last matching rule decides, and a path below an excluded directory is excluded whatever a
 * rule says about the path itself (git's re-include limit): that is why a rule file inside an excluded directory is
 * never read.
 *
 * Caching: a directory's rule files are probed once per scan and parsed once per change of their stamp (size and
 * modification time). [revalidate] starts a new scan, [invalidate] forces one directory to be read again (for a
 * change the stamp cannot see). Thread-safe.
 */
class IgnoreTree(
    private val source: IgnoreFileSource,
    val settings: IgnoreSettings = IgnoreSettings(),
    private val onProblem: (IgnoreProblem) -> Unit = {},
) {
    companion object {
        const val UNIDRIVE_FILE = IgnoreRules.DEFAULT_SOURCE
        const val GIT_FILE = ".gitignore"
    }

    /** The layers that apply below one directory, and whether that directory's rule files hold everything back. */
    private class Chain(
        val layers: List<IgnoreRules>,
        val matcher: IgnoreMatcher,
        val heldBack: IgnoreRule? = null,
    )

    private class DirNode {
        var statEpoch = -1L
        var forceReload = false
        var probes: List<RuleFileProbe> = emptyList()
        var layers: List<IgnoreRules> = emptyList()
        var heldBack: IgnoreRule? = null
        var chain: Chain? = null
        var chainParent: Chain? = null

        @Volatile var memo: Chain? = null

        @Volatile var memoEpoch = -1L
    }

    private val fileNames = if (settings.honorGitignore) listOf(UNIDRIVE_FILE, GIT_FILE) else listOf(UNIDRIVE_FILE)
    private val operational = IgnoreMatcher(listOf(parse(IgnoreDefaults.OPERATIONAL, IgnoreDefaults.OPERATIONAL_SOURCE)))
    private val base: Chain =
        listOf(
            parse(settings.accountRules, IgnoreDefaults.ACCOUNT_SOURCE),
            parse(settings.contentDefaults, IgnoreDefaults.DEFAULT_SOURCE),
        ).let { Chain(it, IgnoreMatcher(it)) }
    private val nodes = ConcurrentHashMap<String, DirNode>()
    private val statEpoch = AtomicLong(0)
    private val chainEpoch = AtomicLong(0)

    /** True when [path] is excluded, directly or through an excluded ancestor directory. */
    fun isIgnored(
        path: String,
        isDir: Boolean = false,
    ): Boolean = explain(path, isDir).ignored

    /**
     * The verdict for [path] (relative to the sync root; a trailing `/` means directory). For a path below an
     * excluded directory it is the topmost excluded ancestor's rule, like `git check-ignore -v`.
     */
    fun explain(
        path: String,
        isDir: Boolean = false,
    ): IgnoreExplanation {
        if (path.isEmpty() || path == "/") return IgnoreExplanation(false, null, null, "")
        if (maybeOperational(path)) {
            val op = operational.explain(path, isDir)
            if (op.ignored) return wrap(op)
        }
        val p = path.removeSuffix("/")
        val dir = isDir || path.endsWith("/")
        var chain = chainFor("", base, chainEpoch.get())
        var start = 0
        while (true) {
            val slash = p.indexOf('/', start)
            val entry = if (slash < 0) p else p.substring(0, slash)
            chain.heldBack?.let { return IgnoreExplanation(true, it, IgnoreLayer.UNREADABLE, entry) }
            if (slash < 0) return wrap(chain.matcher.explainEntry(p, dir))
            val v = chain.matcher.explainEntry(entry, true)
            if (v.ignored) return wrap(v)
            chain = chainFor(entry, chain, chainEpoch.get())
            start = slash + 1
        }
    }

    /**
     * The verdict for the entry [path] on its own, assuming its ancestor directories are not excluded: what a walker
     * that does not descend into excluded directories asks for each entry it meets.
     */
    fun explainEntry(
        path: String,
        isDir: Boolean = false,
    ): IgnoreExplanation {
        if (path.isEmpty() || path == "/") return IgnoreExplanation(false, null, null, "")
        if (maybeOperational(path)) {
            val op = operational.explainEntry(path, isDir)
            if (op.ignored) return wrap(op)
        }
        val p = path.removeSuffix("/")
        val chain = chain(p.substringBeforeLast('/', ""))
        chain.heldBack?.let { return IgnoreExplanation(true, it, IgnoreLayer.UNREADABLE, p) }
        return wrap(chain.matcher.explainEntry(path, isDir))
    }

    /** Starts a new scan: every rule file's stamp is checked again at its next use. */
    fun revalidate() {
        statEpoch.incrementAndGet()
        chainEpoch.incrementAndGet()
    }

    /** Reads the rule files of [dir] (relative to the sync root, "" for the root) again at their next use. */
    fun invalidate(dir: String) {
        val node = nodes[dir.trim('/')] ?: return
        synchronized(node) { node.forceReload = true }
        chainEpoch.incrementAndGet()
    }

    /** Forgets every rule file read so far. */
    fun invalidateAll() {
        nodes.clear()
        chainEpoch.incrementAndGet()
    }

    private fun maybeOperational(path: String): Boolean = path.startsWith(IgnoreDefaults.OPERATIONAL_PREFIX, ignoreCase = true)

    private fun chain(dir: String): Chain {
        val epoch = chainEpoch.get()
        nodes[dir]?.let { n -> if (n.memoEpoch == epoch) n.memo?.let { return it } }
        val parent = if (dir.isEmpty()) base else chain(dir.substringBeforeLast('/', ""))
        return chainFor(dir, parent, epoch)
    }

    /** The chain below [dir], whose parent directory's chain is [parent]. */
    private fun chainFor(
        dir: String,
        parent: Chain,
        epoch: Long,
    ): Chain {
        val node = nodes.computeIfAbsent(dir) { DirNode() }
        if (node.memoEpoch == epoch && node.chainParent === parent) node.memo?.let { return it }
        synchronized(node) {
            val changed = refresh(node, dir)
            var c = node.chain
            if (changed || c == null || node.chainParent !== parent) {
                val held = node.heldBack
                c =
                    when {
                        held != null -> Chain(parent.layers, parent.matcher, held)
                        node.layers.isEmpty() -> parent
                        else -> (node.layers + parent.layers).let { Chain(it, IgnoreMatcher(it)) }
                    }
                node.chain = c
                node.chainParent = parent
            }
            node.memo = c
            node.memoEpoch = epoch
            return c
        }
    }

    /** Re-reads [dir]'s rule files when this scan has not checked them yet. True when its rules changed. */
    private fun refresh(
        node: DirNode,
        dir: String,
    ): Boolean {
        val epoch = statEpoch.get()
        if (node.statEpoch == epoch && !node.forceReload) return false
        val paths = fileNames.map { if (dir.isEmpty()) it else "$dir/$it" }
        val probes = paths.map { source.probe(it) }
        val unchanged = !node.forceReload && probes == node.probes
        node.statEpoch = epoch
        node.forceReload = false
        if (unchanged) return false
        val layers = ArrayList<IgnoreRules>()
        var held: IgnoreRule? = null
        val seen = ArrayList<RuleFileProbe>()
        for ((i, probe) in probes.withIndex()) {
            val loaded = load(paths[i], dir, probe)
            seen.add(loaded.probe)
            loaded.rules?.let { layers.add(it) }
            if (held == null) held = loaded.heldBack
        }
        node.probes = seen
        node.layers = layers
        node.heldBack = held
        return true
    }

    private class Loaded(
        val probe: RuleFileProbe,
        val rules: IgnoreRules? = null,
        val heldBack: IgnoreRule? = null,
    )

    private fun load(
        path: String,
        dir: String,
        probe: RuleFileProbe,
    ): Loaded =
        when (probe) {
            RuleFileProbe.Absent -> Loaded(probe)
            is RuleFileProbe.NotFollowed -> {
                onProblem(IgnoreProblem(path, probe.reason, heldBack = false))
                Loaded(probe)
            }
            is RuleFileProbe.Unreadable -> hold(path, probe, probe.reason)
            is RuleFileProbe.Present -> {
                val bytes =
                    try {
                        source.read(path, settings.maxRuleFileBytes)
                    } catch (ignored: NoSuchFileException) {
                        // Removed since the probe: absent, and probed again at the next scan.
                        return Loaded(RuleFileProbe.Absent)
                    } catch (e: IOException) {
                        return hold(path, probe, e.toString())
                    }
                if (bytes == null) {
                    hold(path, probe, "larger than ${settings.maxRuleFileBytes} bytes")
                } else {
                    Loaded(probe, IgnoreRules.parse(String(bytes, Charsets.UTF_8), source = path, base = dir, options = settings.options))
                }
            }
        }

    private fun hold(
        path: String,
        probe: RuleFileProbe,
        reason: String,
    ): Loaded {
        onProblem(IgnoreProblem(path, reason, heldBack = true))
        return Loaded(probe, heldBack = IgnoreRule(path, 0, reason, negated = false, dirOnly = false))
    }

    private fun parse(
        lines: List<String>,
        source: String,
    ): IgnoreRules = IgnoreRules.parse(lines.joinToString("\n"), source = source, options = settings.options)

    private fun wrap(v: IgnoreVerdict): IgnoreExplanation {
        val r = v.rule ?: return IgnoreExplanation(v.ignored, null, null, v.decidedAt)
        val layer =
            when (r.source) {
                IgnoreDefaults.OPERATIONAL_SOURCE -> IgnoreLayer.OPERATIONAL
                IgnoreDefaults.DEFAULT_SOURCE -> IgnoreLayer.DEFAULT
                IgnoreDefaults.ACCOUNT_SOURCE -> IgnoreLayer.ACCOUNT
                else -> if (r.source.endsWith(GIT_FILE)) IgnoreLayer.GITIGNORE else IgnoreLayer.UNIDRIVE_IGNORE
            }
        return IgnoreExplanation(v.ignored, r, layer, v.decidedAt)
    }
}
