package org.krost.unidrive.sync.ignore

import java.text.Normalizer

/** How a rule set treats names; the same options apply to every layer of one [IgnoreMatcher]. */
data class IgnoreOptions(
    /**
     * git's `core.ignorecase`: fold ASCII case when comparing. git folds ASCII only, so `É` and `é` stay
     * different names even then; this matcher does the same (see DEVIATIONS.md).
     */
    val ignoreCase: Boolean = false,
    /**
     * Normalise rule text and queried paths to NFC first. git does not (outside macOS), so with this on, an NFC
     * rule matches an NFD name. UniDrive names are NFC-canonical, so a caller that holds canonical paths turns
     * this on to make hand-typed NFD rules work. Off by default: the default is git's byte semantics.
     */
    val normalizeNfc: Boolean = false,
)

/**
 * One rule of a rule file, as `git check-ignore -v` reports it.
 *
 * @property source the file the rule came from, as the caller named it (git prints e.g. `.gitignore`).
 * @property line 1-based line number in that file.
 * @property text the rule as git prints it: `!` prefix, pattern, trailing `/`; trailing spaces already removed.
 */
data class IgnoreRule(
    val source: String,
    val line: Int,
    val text: String,
    val negated: Boolean,
    val dirOnly: Boolean,
)

/**
 * The parsed rules of ONE rule file, in file order. Nested-file layering (which files apply where, how they are
 * discovered and cached) is the caller's concern and lives above this class; an [IgnoreMatcher] orders layers.
 *
 * @property base the directory the file sits in, relative to the matching root, `/`-separated, no leading or
 *   trailing slash; "" for the root. The rules apply only to paths below it, and a rule containing `/` is
 *   anchored there.
 */
class IgnoreRules private constructor(
    val source: String,
    val base: String,
    val options: IgnoreOptions,
    internal val compiled: List<CompiledRule>,
) {
    val rules: List<IgnoreRule> get() = compiled.map { it.rule }

    private val baseBytes: ByteArray = base.toByteArray(Charsets.UTF_8)

    /**
     * The last rule of this file that matches [path] (git's `last_matching_pattern_from_list`), or null. Looks at
     * `path[0 until pathLen]` only and does not look at ancestors.
     */
    internal fun lastMatch(
        path: ByteArray,
        pathLen: Int,
        isDir: Boolean,
    ): CompiledRule? {
        val baseLen = baseBytes.size
        if (baseLen > 0) {
            if (pathLen < baseLen + 1 || path[baseLen] != SLASH) return null
            if (!bytesEqual(baseBytes, 0, path, 0, baseLen, options.ignoreCase)) return null
        }
        var nameStart = pathLen
        while (nameStart > 0 && path[nameStart - 1] != SLASH) nameStart--
        for (i in compiled.indices.reversed()) {
            val c = compiled[i]
            if (c.dirOnly && !isDir) continue
            val hit =
                if (c.noDir) {
                    c.matchBasename(path, nameStart, pathLen, options.ignoreCase)
                } else {
                    c.matchPathname(path, pathLen, baseLen, options.ignoreCase)
                }
            if (hit) return c
        }
        return null
    }

    companion object {
        private const val SLASH = '/'.code.toByte()

        /**
         * Parse [text] the way git reads an ignore file: lines end at LF (a CR before it is dropped), a leading
         * UTF-8 byte-order mark is skipped, `#` at the start of a line begins a comment, blank lines are
         * skipped, trailing spaces are dropped unless escaped by a backslash.
         */
        fun parse(
            text: String,
            source: String = ".unidrive.ignore",
            base: String = "",
            options: IgnoreOptions = IgnoreOptions(),
        ): IgnoreRules {
            require(!base.startsWith("/") && !base.endsWith("/")) { "base is relative without surrounding slashes: '$base'" }
            val body = if (text.startsWith("﻿")) text.substring(1) else text
            val out = ArrayList<CompiledRule>()
            var lineNo = 0
            for (raw in body.split('\n')) {
                lineNo++
                if (raw.isEmpty() || raw[0] == '#') continue
                var line = if (raw.endsWith("\r")) raw.dropLast(1) else raw
                if (options.normalizeNfc) line = Normalizer.normalize(line, Normalizer.Form.NFC)
                line = trimTrailingSpaces(line)
                if (line.isEmpty()) continue
                out.add(CompiledRule.compile(line, source, lineNo))
            }
            return IgnoreRules(source, base, options, out)
        }

        /** git's `trim_trailing_spaces`: drop trailing spaces, but not one escaped by a backslash. */
        internal fun trimTrailingSpaces(line: String): String {
            var lastSpace = -1
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    ' ' -> if (lastSpace < 0) lastSpace = i
                    '\\' -> {
                        i++
                        // A trailing lone backslash ends the scan and leaves the line untouched.
                        if (i >= line.length) return line
                        lastSpace = -1
                    }
                    else -> lastSpace = -1
                }
                i++
            }
            return if (lastSpace >= 0) line.substring(0, lastSpace) else line
        }
    }
}

internal fun bytesEqual(
    a: ByteArray,
    aOff: Int,
    b: ByteArray,
    bOff: Int,
    n: Int,
    fold: Boolean,
): Boolean {
    for (i in 0 until n) {
        val x = a[aOff + i].toInt() and 0xFF
        val y = b[bOff + i].toInt() and 0xFF
        if (x != y && !(fold && Wildmatch.lowerAscii(x) == Wildmatch.lowerAscii(y))) return false
    }
    return true
}

/** A rule with git's parse-time flags (`parse_path_pattern`) precomputed. */
internal class CompiledRule private constructor(
    val rule: IgnoreRule,
    /** The pattern without its `!` and without a trailing `/`, as UTF-8 bytes. */
    private val pattern: ByteArray,
    private val nowildcardLen: Int,
    val noDir: Boolean,
    val dirOnly: Boolean,
    private val endsWith: Boolean,
) {
    /** git's `match_basename`: [path] from [start] to [end] is the last path component. */
    fun matchBasename(
        path: ByteArray,
        start: Int,
        end: Int,
        fold: Boolean,
    ): Boolean {
        val baseLen = end - start
        val patLen = pattern.size
        return when {
            nowildcardLen == patLen -> patLen == baseLen && bytesEqual(pattern, 0, path, start, baseLen, fold)
            endsWith ->
                patLen - 1 <= baseLen &&
                    bytesEqual(pattern, 1, path, start + baseLen - (patLen - 1), patLen - 1, fold)
            else -> Wildmatch.matches(pattern, 0, patLen, path, start, end, pathname = false, fold = fold)
        }
    }

    /** git's `match_pathname`: the rule is anchored below a base directory of [baseLen] bytes (0 = root). */
    fun matchPathname(
        path: ByteArray,
        pathLen: Int,
        baseLen: Int,
        fold: Boolean,
    ): Boolean {
        var pStart = 0
        var prefix = nowildcardLen
        if (pattern.isNotEmpty() && pattern[0] == SLASH) {
            pStart++
            prefix--
        }
        var nameLen = if (baseLen > 0) pathLen - baseLen - 1 else pathLen
        var nameStart = pathLen - nameLen
        if (prefix > 0) {
            if (prefix > nameLen) return false
            if (!bytesEqual(pattern, pStart, path, nameStart, prefix, fold)) return false
            pStart += prefix
            nameStart += prefix
            nameLen -= prefix
            if (pStart == pattern.size && nameLen == 0) return true
        }
        return Wildmatch.matches(pattern, pStart, pattern.size, path, nameStart, nameStart + nameLen, pathname = true, fold = fold)
    }

    companion object {
        private const val SLASH = '/'.code.toByte()

        /** [line] is already trimmed, non-empty and not a comment. */
        fun compile(
            line: String,
            source: String,
            lineNo: Int,
        ): CompiledRule {
            var p = line
            val negated = p.startsWith("!")
            if (negated) p = p.substring(1)
            val dirOnly = p.endsWith("/")
            val body = if (dirOnly) p.dropLast(1) else p
            val noDir = '/' !in body
            val bytes = body.toByteArray(Charsets.UTF_8)
            // simple_length() runs over the whole string, trailing slash included, and is then clamped.
            val full = p.toByteArray(Charsets.UTF_8)
            val simple = simpleLength(full, 0).coerceAtMost(bytes.size)
            val starts = full.isNotEmpty() && full[0] == '*'.code.toByte() && simpleLength(full, 1) == full.size - 1
            val rule = IgnoreRule(source, lineNo, line, negated, dirOnly)
            return CompiledRule(rule, bytes, simple, noDir, dirOnly, starts)
        }

        private fun simpleLength(
            s: ByteArray,
            from: Int,
        ): Int {
            var i = from
            while (i < s.size && !Wildmatch.isGlobSpecial(s[i].toInt() and 0xFF)) i++
            return i - from
        }
    }
}
