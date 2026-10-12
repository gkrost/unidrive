package org.krost.unidrive.sync.ignore

import org.krost.unidrive.engine.UploadIgnoreCheck
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The ignore rules of one sync root as the upload path asks them (docs/dev/specs/ignore-layering-design.md, "The
 * upload path"): an [IgnoreTree] asked with the engine's logical paths (`/a/b`).
 *
 * - **No restart for a rule change.** The tree is revalidated at most every [revalidateAfterMs]: the next question
 *   after that probes the rule files again, and an edited, added or removed file takes effect.
 * - **Never silent.** A rule file that holds its directory back (unreadable, too large) is logged as a WARN, at most
 *   once per file every [warnEveryMs], and [heldBack] lists the directories for `daemon.status`.
 *
 * Thread-safe, like the tree.
 */
class UploadIgnore(
    source: IgnoreFileSource,
    val settings: IgnoreSettings,
    private val revalidateAfterMs: Long = DEFAULT_REVALIDATE_AFTER_MS,
    private val warnEveryMs: Long = DEFAULT_WARN_EVERY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : UploadIgnoreCheck {
    private val log = LoggerFactory.getLogger(UploadIgnore::class.java)
    private val tree = IgnoreTree(source, settings, ::report)
    private val lastRevalidate = AtomicLong(Long.MIN_VALUE)
    private val warned = ConcurrentHashMap<String, Long>()

    /** True when the upload of [path] (a logical path, `/a/b`) is held back by an ignore rule. */
    override fun isIgnored(
        path: String,
        isDir: Boolean,
    ): Boolean = explain(path, isDir).ignored

    /** The verdict for [path] (a logical path); the paths inside it are relative to the sync root, as the tree's. */
    fun explain(
        path: String,
        isDir: Boolean = false,
    ): IgnoreExplanation {
        maybeRevalidate()
        return tree.explain(path.trim('/'), isDir)
    }

    /** True when [path]'s name is a rule file this account reads (`.unidrive.ignore`, `.gitignore` with the opt-in). */
    override fun isRuleFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == IgnoreTree.UNIDRIVE_FILE || (settings.honorGitignore && name == IgnoreTree.GIT_FILE)
    }

    /** The rule file [path] was written: its folder's rule files are read again at their next use. */
    override fun ruleFileWritten(path: String) = tree.invalidate(path.trim('/').substringBeforeLast('/', ""))

    /** The directories held back now (re-probed when a rule file may have changed since), as logical paths. */
    fun heldBack(): List<HeldBackDir> {
        maybeRevalidate()
        return tree.heldBack().map { HeldBackDir("/" + it.dir, "/" + it.file, it.reason) }
    }

    /** When the rule files were last revalidated (epoch ms), null before the first question. */
    val checkedAtMs: Long? get() = lastRevalidate.get().takeIf { it != Long.MIN_VALUE }

    private fun maybeRevalidate() {
        val now = clock()
        val last = lastRevalidate.get()
        if (last != Long.MIN_VALUE && now - last < revalidateAfterMs) return
        if (lastRevalidate.compareAndSet(last, now)) tree.revalidate()
    }

    private fun report(problem: IgnoreProblem) {
        val now = clock()
        val previous = warned[problem.file]
        if (previous != null && now - previous < warnEveryMs) return
        if (warned.size >= WARNED_CAP) warned.clear()
        warned[problem.file] = now
        if (problem.heldBack) {
            val dir = problem.file.substringBeforeLast('/', "")
            log.warn(
                "ignore rule file /{} cannot be read ({}): nothing below /{} is uploaded until it can be",
                problem.file,
                problem.reason,
                dir,
            )
        } else {
            log.info("ignore rule file /{} is not read ({})", problem.file, problem.reason)
        }
    }

    companion object {
        const val DEFAULT_REVALIDATE_AFTER_MS = 5_000L
        const val DEFAULT_WARN_EVERY_MS = 15 * 60_000L
        private const val WARNED_CAP = 1_000

        /**
         * The settings of a profile: its `honor_gitignore` and `ignore_rules` keys, with case folding where the local
         * file system folds case (Windows, macOS) and NFC names (the engine's names are NFC).
         */
        fun settingsFor(
            honorGitignore: Boolean,
            accountRules: List<String>,
        ): IgnoreSettings =
            IgnoreSettings(
                honorGitignore = honorGitignore,
                accountRules = accountRules,
                options = IgnoreOptions(ignoreCase = foldsCase(), normalizeNfc = true),
            )

        private fun foldsCase(): Boolean =
            File.separatorChar == '\\' || System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
    }
}

/**
 * The rule files of a mount, read from its hydration cache ([cacheDir]): a rule file's bytes are there once it was
 * written through the mount or opened. Nothing is downloaded to read one. A rule file the cloud holds but the cache
 * does not ([inCloud], asked with the logical path) cannot be read, so its directory is held back (fail closed) until
 * the file is opened once.
 */
class CacheIgnoreSource(
    cacheDir: Path,
    private val inCloud: (path: String) -> Boolean,
) : IgnoreFileSource {
    private val files = FileSystemIgnoreSource(cacheDir)

    override fun probe(path: String): RuleFileProbe =
        when (val probe = files.probe(path)) {
            RuleFileProbe.Absent -> if (inCloud("/$path")) RuleFileProbe.Unreadable(NOT_DOWNLOADED) else probe
            else -> probe
        }

    override fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? = files.read(path, maxBytes)

    companion object {
        const val NOT_DOWNLOADED = "in the cloud but not downloaded to this computer; open it once"
    }
}
