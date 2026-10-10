package org.krost.unidrive.sync.ignore

import java.text.Normalizer

/**
 * The answer for one path.
 *
 * @property ignored the path is excluded.
 * @property rule the rule that decided, null when no rule matched (then [ignored] is false). For a path below an
 *   excluded directory it is that directory's rule.
 * @property decidedAt the path the rule matched: the queried path itself, or the excluded ancestor directory.
 */
data class IgnoreVerdict(
    val ignored: Boolean,
    val rule: IgnoreRule?,
    val decidedAt: String,
)

/**
 * `.gitignore` semantics over a stack of rule files.
 *
 * [layers] are tried deepest [IgnoreRules.base] first (the caller's order breaks ties between files in the same
 * directory: earlier wins). A layer applies only to paths strictly below its base. Within the first layer that has a
 * matching rule, the last matching rule decides. A path below an excluded directory is excluded, whatever a later
 * rule says about the path itself; [explain] then reports the topmost excluded ancestor.
 *
 * Paths are relative to the matching root, `/`-separated, without `.`/`..`/empty components. A trailing `/` means
 * "directory", the same as `isDir = true`. The matcher is immutable and safe to share between threads.
 */
class IgnoreMatcher(
    layers: List<IgnoreRules>,
) {
    private val layers: List<Layer> =
        layers
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<IgnoreRules>> { depth(it.value.base) }.thenBy { it.index })
            .map { Layer(it.value) }

    private val needsNfc = this.layers.any { it.nfc }

    /** True when [path] is excluded (directly or through an excluded ancestor directory). */
    fun isIgnored(
        path: String,
        isDir: Boolean = false,
    ): Boolean = explain(path, isDir).ignored

    /** The verdict for [path], including the rule of an excluded ancestor directory. */
    fun explain(
        path: String,
        isDir: Boolean = false,
    ): IgnoreVerdict {
        val q = Query.of(path, isDir, needsNfc) ?: return IgnoreVerdict(false, null, "")
        for (level in 0 until q.depth - 1) {
            val r = entry(q, level, true)
            if (r != null && !r.negated) return IgnoreVerdict(true, r, q.prefix(level))
        }
        val r = entry(q, q.depth - 1, q.isDir)
        return IgnoreVerdict(r != null && !r.negated, r, q.text)
    }

    /**
     * The verdict for the entry [path] on its own, ignoring what its ancestor directories say: what a walker that
     * does not descend into excluded directories needs for each entry it meets.
     */
    fun explainEntry(
        path: String,
        isDir: Boolean = false,
    ): IgnoreVerdict {
        val q = Query.of(path, isDir, needsNfc) ?: return IgnoreVerdict(false, null, "")
        val r = entry(q, q.depth - 1, q.isDir)
        return IgnoreVerdict(r != null && !r.negated, r, q.text)
    }

    /** The deciding rule for the prefix of [q] that ends with component [level] (0-based), or null. */
    private fun entry(
        q: Query,
        level: Int,
        isDir: Boolean,
    ): IgnoreRule? {
        for (layer in layers) {
            val v = q.variant(layer.variant)
            val end = if (level == q.depth - 1) v.bytes.size else v.slashes[level]
            val start = layer.start(v.bytes, end) ?: continue
            val nameStart = if (level == 0) 0 else v.slashes[level - 1] + 1
            val rules = layer.rules
            for (k in rules.indices.reversed()) {
                val c = rules[k]
                if (c.rule.dirOnly && !isDir) continue
                val hit = if (c.anchored) c.glob.matches(v.bytes, start, end) else c.glob.matches(v.bytes, nameStart, end)
                if (hit) return c.rule
            }
        }
        return null
    }

    private class Layer(
        r: IgnoreRules,
    ) {
        val rules: List<CompiledRule> = r.compiled
        val nfc = r.options.normalizeNfc
        val variant = (if (nfc) 2 else 0) + (if (r.options.ignoreCase) 1 else 0)
        private val base: ByteArray =
            r.base.toByteArray(Charsets.UTF_8).let { if (r.options.ignoreCase) GlobPattern.foldAscii(it) else it }

        /** Start of the base-relative part of `bytes[0 until end]`, or null when that path is not below the base. */
        fun start(
            bytes: ByteArray,
            end: Int,
        ): Int? {
            if (base.isEmpty()) return 0
            if (end <= base.size + 1 || bytes[base.size] != '/'.code.toByte()) return null
            for (i in base.indices) if (bytes[i] != base[i]) return null
            return base.size + 1
        }
    }

    /** One query path in the spellings the layers need (raw or NFC, as-is or ASCII-folded), computed on demand. */
    private class Query(
        val text: String,
        val isDir: Boolean,
        val depth: Int,
        private val nfcText: String?,
    ) {
        private val variants = arrayOfNulls<Variant>(4)

        fun variant(id: Int): Variant {
            variants[id]?.let { return it }
            val s = if (id >= 2) nfcText!! else text
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (id and 1 == 1) GlobPattern.foldAscii(bytes)
            val v = Variant(bytes, slashes(bytes))
            variants[id] = v
            return v
        }

        /** The path up to and including component [level]. */
        fun prefix(level: Int): String {
            var seen = -1
            for (i in text.indices) {
                if (text[i] == '/') {
                    seen++
                    if (seen == level) return text.substring(0, i)
                }
            }
            return text
        }

        private fun slashes(b: ByteArray): IntArray {
            val out = IntArray(depth - 1)
            var n = 0
            for (i in b.indices) if (b[i] == '/'.code.toByte()) out[n++] = i
            return out
        }

        companion object {
            fun of(
                path: String,
                isDir: Boolean,
                needsNfc: Boolean,
            ): Query? {
                // The root has no matchable entry, but malformed paths must not be silently
                // rewritten into a different root-relative entry.
                if (path.isEmpty() || path == "/") return null
                require(!path.startsWith('/')) { "path must be relative to the matching root" }
                var p = path
                var dir = isDir
                if (p.endsWith("/")) {
                    dir = true
                    p = p.dropLast(1)
                }
                // A backslash is an ordinary character of a name (git on POSIX reads it so; a Linux file may be called a\b), not a separator: the engine's paths are '/'-separated on every OS.
                require(p.isNotEmpty()) { "path must use canonical '/'-separated components" }
                require(p.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "path must use canonical '/'-separated components"
                }
                val nfc = if (needsNfc) Normalizer.normalize(p, Normalizer.Form.NFC) else null
                return Query(p, dir, p.count { it == '/' } + 1, nfc)
            }
        }
    }

    private class Variant(
        val bytes: ByteArray,
        /** Byte index of each slash, in order. */
        val slashes: IntArray,
    )

    private companion object {
        fun depth(base: String): Int = if (base.isEmpty()) 0 else base.count { it == '/' } + 1
    }
}
