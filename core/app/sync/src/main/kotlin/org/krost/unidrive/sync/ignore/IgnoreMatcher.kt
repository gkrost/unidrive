package org.krost.unidrive.sync.ignore

import java.text.Normalizer

/**
 * What the rules say about one path.
 *
 * @property ignored true when the path is excluded (a plain rule won, or an ancestor directory is excluded).
 * @property rule the winning rule, null when nothing matched. A negated rule means "explicitly not ignored".
 * @property decidedAt the path the winning rule matched: the queried path itself, or the topmost excluded
 *   ancestor directory (git's rule: nothing below an excluded directory can be re-included). Null without a rule.
 */
data class IgnoreVerdict(
    val ignored: Boolean,
    val rule: IgnoreRule?,
    val decidedAt: String?,
) {
    companion object {
        internal val NO_MATCH = IgnoreVerdict(ignored = false, rule = null, decidedAt = null)
    }
}

/**
 * Decides whether a path is ignored, with git's `.gitignore` pattern semantics: anchoring, trailing `/` for
 * directories, the `**` forms, `!` negation, escapes, comments, and the rule that a path below an excluded
 * directory stays excluded whatever a later `!` says.
 *
 * Paths are relative to the matching root, `/`-separated, with no leading slash. A trailing `/` on a path means
 * "this is a directory" and is equivalent to passing `isDir = true`.
 *
 * Layers are given highest priority first (git: a deeper file beats a shallower one, which beats
 * `.git/info/exclude`). The first layer that has a matching rule decides; inside a layer the last matching rule
 * wins. Which files exist, where, and when they are re-read is not this class's business.
 *
 * Immutable and thread-safe once built.
 */
class IgnoreMatcher(
    private val layers: List<IgnoreRules>,
) {
    private val options: IgnoreOptions = layers.firstOrNull()?.options ?: IgnoreOptions()

    init {
        require(layers.all { it.options == options }) { "all layers of one matcher share the same IgnoreOptions" }
    }

    /** True when [path] is excluded. See [explain]. */
    fun isIgnored(
        path: String,
        isDir: Boolean = false,
    ): Boolean = explain(path, isDir).ignored

    /**
     * The verdict for [path], honouring excluded ancestor directories exactly as `git check-ignore` does: the
     * topmost excluded ancestor decides, its rule is the answer.
     */
    fun explain(
        path: String,
        isDir: Boolean = false,
    ): IgnoreVerdict = decide(path, isDir, withAncestors = true)

    /**
     * The verdict for [path] looked at on its own, without examining its ancestors. For a walker that prunes: it
     * asks about each directory as it enters it and never descends into an excluded one, so by the time it asks
     * about an entry its ancestors are known not to be excluded. Cheaper than [explain] by the path depth.
     */
    fun explainEntry(
        path: String,
        isDir: Boolean = false,
    ): IgnoreVerdict = decide(path, isDir, withAncestors = false)

    private fun decide(
        rawPath: String,
        rawIsDir: Boolean,
        withAncestors: Boolean,
    ): IgnoreVerdict {
        var isDir = rawIsDir
        var path = rawPath
        if (path.endsWith("/")) {
            isDir = true
            path = path.trimEnd('/')
        }
        if (path.isEmpty() || layers.isEmpty()) return IgnoreVerdict.NO_MATCH
        if (options.normalizeNfc && !Normalizer.isNormalized(path, Normalizer.Form.NFC)) {
            path = Normalizer.normalize(path, Normalizer.Form.NFC)
        }
        val bytes = path.toByteArray(Charsets.UTF_8)
        if (withAncestors) {
            // The topmost excluded ancestor wins: git stops at the first one it finds going down.
            for (i in bytes.indices) {
                if (bytes[i] != SLASH) continue
                val hit = lastMatch(bytes, i, isDir = true)
                if (hit != null && !hit.rule.negated) {
                    return IgnoreVerdict(ignored = true, rule = hit.rule, decidedAt = String(bytes, 0, i, Charsets.UTF_8))
                }
            }
        }
        val hit = lastMatch(bytes, bytes.size, isDir) ?: return IgnoreVerdict.NO_MATCH
        return IgnoreVerdict(ignored = !hit.rule.negated, rule = hit.rule, decidedAt = path)
    }

    private fun lastMatch(
        path: ByteArray,
        pathLen: Int,
        isDir: Boolean,
    ): CompiledRule? {
        for (layer in layers) {
            val hit = layer.lastMatch(path, pathLen, isDir)
            if (hit != null) return hit
        }
        return null
    }

    private companion object {
        private const val SLASH = '/'.code.toByte()
    }
}
