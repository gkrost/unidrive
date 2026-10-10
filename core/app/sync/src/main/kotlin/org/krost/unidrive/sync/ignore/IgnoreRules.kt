package org.krost.unidrive.sync.ignore

import java.text.Normalizer

/**
 * One rule line, as `git check-ignore -v` reports it.
 *
 * @property source the rule file it came from (as named by the caller).
 * @property line 1-based line number in that file.
 * @property text the line as written, trailing unescaped spaces removed (with its `!` and trailing `/`).
 * @property negated the line starts with `!`: a match re-includes.
 * @property dirOnly the pattern ends with `/`: it matches directories only.
 */
data class IgnoreRule(
    val source: String,
    val line: Int,
    val text: String,
    val negated: Boolean,
    val dirOnly: Boolean,
)

/** A rule with its compiled pattern. */
internal class CompiledRule(
    val rule: IgnoreRule,
    /** Matched against the path relative to the rule file's directory; otherwise against the last component. */
    val anchored: Boolean,
    val glob: GlobPattern,
)

/**
 * The rules of one rule file, in file order.
 *
 * @property source the file's name as reported in [IgnoreRule.source].
 * @property base the directory the file lives in, relative to the matching root, "" for the root. Its rules apply
 *   to paths below that directory only, and anchored patterns are relative to it.
 */
class IgnoreRules private constructor(
    val source: String,
    val base: String,
    val options: IgnoreOptions,
    val rules: List<IgnoreRule>,
    internal val compiled: List<CompiledRule>,
) {
    companion object {
        const val DEFAULT_SOURCE = ".unidrive.ignore"

        /**
         * Parses rule-file [text] with the line syntax of `gitignore`:
         *  - a UTF-8 byte-order mark at the very start is skipped; lines end at LF, and a CR before it is dropped;
         *  - blank lines and lines starting with `#` (column 0 only) are skipped;
         *  - trailing spaces are removed unless escaped with a backslash ([trimTrailingSpaces]);
         *  - a leading `!` negates (re-includes); `\!` and `\#` are the literal characters;
         *  - a trailing `/` restricts the pattern to directories;
         *  - a slash at the start or in the middle anchors the pattern to [base]; otherwise it matches the last
         *    path component at any depth;
         *  - `*`, `?`, `[...]` never match a slash; `**` as a whole component spans directories.
         */
        fun parse(
            text: String,
            source: String = DEFAULT_SOURCE,
            base: String = "",
            options: IgnoreOptions = IgnoreOptions(),
        ): IgnoreRules {
            var body = if (options.normalizeNfc) Normalizer.normalize(text, Normalizer.Form.NFC) else text
            if (body.startsWith(BOM)) body = body.substring(1)
            val rules = ArrayList<IgnoreRule>()
            val compiled = ArrayList<CompiledRule>()
            var lineNo = 0
            for (raw in body.split('\n')) {
                lineNo++
                val line = raw.removeSuffix("\r")
                if (line.isEmpty() || line.startsWith("#")) continue
                val trimmed = trimTrailingSpaces(line)
                if (trimmed.isEmpty()) continue
                val c = compile(trimmed, source, lineNo, options)
                rules.add(c.rule)
                compiled.add(c)
            }
            val normBase = base.trim('/').let { if (options.normalizeNfc) Normalizer.normalize(it, Normalizer.Form.NFC) else it }
            return IgnoreRules(source, normBase, options, rules, compiled)
        }

        /**
         * Removes trailing spaces, except a space escaped with a backslash (which is kept, and anything after it
         * removed). Tabs are not spaces here. A trailing lone backslash stays.
         */
        fun trimTrailingSpaces(line: String): String {
            var end = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    end = i + 2
                    i += 2
                    continue
                }
                if (c != ' ') end = i + 1
                i++
            }
            return line.substring(0, end)
        }

        private const val BOM = "\uFEFF"

        private fun compile(
            text: String,
            source: String,
            line: Int,
            options: IgnoreOptions,
        ): CompiledRule {
            var p = text
            val negated = p.startsWith("!")
            if (negated) p = p.substring(1)
            val dirOnly = p.endsWith("/") && !endsWithEscapedSlash(p)
            if (dirOnly) p = p.dropLast(1)
            val anchored = p.contains('/')
            if (p.startsWith("/")) p = p.substring(1)
            val c = GlobPattern.compile(p, options.ignoreCase, leadingDirsAsBasename = anchored)
            val rule = IgnoreRule(source, line, text, negated, dirOnly)
            return CompiledRule(rule, anchored && !c.basename, c.glob)
        }

        /** "a\/" ends with an escaped slash, which is a literal, not the directory marker. */
        private fun endsWithEscapedSlash(p: String): Boolean {
            var k = p.length - 2
            var backslashes = 0
            while (k >= 0 && p[k] == '\\') {
                backslashes++
                k--
            }
            return backslashes % 2 == 1
        }
    }
}
