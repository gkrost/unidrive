package org.krost.unidrive.sync.ignore

/**
 * A port of git's `wildmatch()` (wildmatch.c, the rsync-derived matcher behind `.gitignore` patterns).
 *
 * It works on bytes, exactly as git does: `?` and a bracket member are one UTF-8 *byte*, not one character,
 * and case folding is ASCII only. That is deliberate, the oracle test compares this class with `git
 * check-ignore`, and see `DEVIATIONS.md` for what the byte semantics mean for non-ASCII names.
 *
 * Both arrays are read as if followed by a NUL terminator, like the C original: an index at or past the end
 * reads as 0, and an embedded 0 ends the pattern (git never has one, it works on C strings).
 */
internal class Wildmatch private constructor(
    private val pat: ByteArray,
    private val pEnd: Int,
    private val txt: ByteArray,
    private val tEnd: Int,
    private val pathname: Boolean,
    private val fold: Boolean,
) {
    private fun pc(i: Int): Int = if (i in 0 until pEnd) pat[i].toInt() and 0xFF else 0

    private fun tc(i: Int): Int = if (i in 0 until tEnd) txt[i].toInt() and 0xFF else 0

    /** The star run is preceded by a component boundary: the pattern start or a slash ([prevP] is the byte before it). */
    private fun startsComponent(prevP: Int): Boolean = prevP < 0 || pc(prevP) == SLASH

    /** The star run is followed by a component boundary: the pattern end, a slash, or an escaped slash. */
    private fun endsComponent(p: Int): Boolean = pc(p) == 0 || pc(p) == SLASH || (pc(p) == BACKSLASH && pc(p + 1) == SLASH)

    /** In a bracket, a dash is a range operator only when something other than "]" follows it. */
    private fun rangeEndFollows(i: Int): Boolean = pc(i) != 0 && pc(i) != CLOSE_BRACKET

    private fun slashAtOrAfter(from: Int): Int {
        var i = from
        while (i < tEnd) {
            if (txt[i] == SLASH_BYTE) return i
            i++
        }
        return -1
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun dowild(
        p0: Int,
        t0: Int,
    ): Int {
        var p = p0
        var text = t0
        while (true) {
            var pCh = pc(p)
            if (pCh == 0) break
            var tCh = tc(text)
            if (tCh == 0 && pCh != STAR) return ABORT_ALL
            if (fold && isUpper(tCh)) tCh = tCh + CASE_DELTA
            if (fold && isUpper(pCh)) pCh = pCh + CASE_DELTA
            when (pCh) {
                BACKSLASH -> {
                    // Literal match with the following character; a trailing backslash compares against NUL.
                    p++
                    pCh = pc(p)
                    if (tCh != pCh) return NOMATCH
                }
                QUESTION -> if (pathname && tCh == SLASH) return NOMATCH
                STAR -> {
                    p++
                    val matchSlash: Boolean
                    if (pc(p) == STAR) {
                        val prevP = p - 2
                        p++
                        while (pc(p) == STAR) p++
                        if (startsComponent(prevP) && endsComponent(p)) {
                            // "foo/**/bar": "**/" may match nothing, so try the rest against the text as it is.
                            if (pc(p) == SLASH && dowild(p + 1, text) == MATCH) return MATCH
                            matchSlash = true
                        } else {
                            matchSlash = false
                        }
                    } else {
                        matchSlash = !pathname
                    }
                    if (pc(p) == 0) {
                        // A trailing "**" matches everything, a trailing "*" only text without a further slash.
                        if (!matchSlash && slashAtOrAfter(text) >= 0) return NOMATCH
                        return MATCH
                    } else if (!matchSlash && pc(p) == SLASH) {
                        // One asterisk before a slash consumes up to the next slash; the loop's own p++ eats it.
                        val slash = slashAtOrAfter(text)
                        if (slash < 0) return NOMATCH
                        text = slash
                    } else {
                        return starLoop(p, text, tCh, matchSlash)
                    }
                }
                OPEN_BRACKET -> {
                    val r = bracket(p, tCh)
                    if (r < 0) return r
                    p = r shr 1
                    // The low bit says whether the class matched; the next check needs the verdict only.
                    if ((r and 1) == 0) return NOMATCH
                }
                else -> if (tCh != pCh) return NOMATCH
            }
            text++
            p++
        }
        return if (tc(text) != 0) NOMATCH else MATCH
    }

    /** The `while (1)` search of the `*` case: try the rest of the pattern at each text position. */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun starLoop(
        p: Int,
        text0: Int,
        tCh0: Int,
        matchSlash: Boolean,
    ): Int {
        var text = text0
        var tCh = tCh0
        while (true) {
            if (tCh == 0) break
            // Advance faster when the star is followed by a literal; do not run past a slash unless it may match.
            if (!isGlobSpecial(pc(p))) {
                var pCh = pc(p)
                if (fold && isUpper(pCh)) pCh += CASE_DELTA
                while (true) {
                    tCh = tc(text)
                    if (tCh == 0 || !(matchSlash || tCh != SLASH)) break
                    if (fold && isUpper(tCh)) tCh += CASE_DELTA
                    if (tCh == pCh) break
                    text++
                }
                // Text ran out without the literal: no later start can find it either, so stop the whole match
                // (this prunes the exponential "*a*a*a...b" search). Stopped at a slash: only this star fails.
                if (tCh != pCh) return if (tCh == 0) ABORT_ALL else NOMATCH
            }
            val matched = dowild(p, text)
            if (matched != NOMATCH) {
                if (!matchSlash || matched != ABORT_TO_STARSTAR) return matched
            } else if (!matchSlash && tCh == SLASH) {
                return ABORT_TO_STARSTAR
            }
            text++
            tCh = tc(text)
        }
        return ABORT_ALL
    }

    /**
     * The `[...]` case. `p` is at the opening bracket. Returns a negative abort code, or `(newP shl 1) or hit`
     * where `newP` is the index of the closing bracket and `hit` is 1 when the class accepts [tCh]
     * (already accounting for negation and for a slash under `pathname`).
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "ReturnCount")
    private fun bracket(
        pOpen: Int,
        tCh: Int,
    ): Int {
        var p = pOpen + 1
        var pCh = pc(p)
        if (pCh == CARET) pCh = BANG
        val negated = pCh == BANG
        if (negated) {
            p++
            pCh = pc(p)
        }
        var prevCh = 0
        var matched = false
        while (true) {
            if (pCh == 0) return ABORT_ALL
            if (pCh == BACKSLASH) {
                p++
                pCh = pc(p)
                if (pCh == 0) return ABORT_ALL
                if (tCh == pCh) matched = true
            } else if (pCh == DASH && prevCh != 0 && rangeEndFollows(p + 1)) {
                p++
                pCh = pc(p)
                if (pCh == BACKSLASH) {
                    p++
                    pCh = pc(p)
                    if (pCh == 0) return ABORT_ALL
                }
                if (tCh <= pCh && tCh >= prevCh) {
                    matched = true
                } else if (fold && isLower(tCh)) {
                    val upper = tCh - CASE_DELTA
                    if (upper <= pCh && upper >= prevCh) matched = true
                }
                pCh = 0 // prev_ch becomes 0 for the next member
            } else if (pCh == OPEN_BRACKET && pc(p + 1) == COLON) {
                p += 2
                val s = p
                while (true) {
                    pCh = pc(p)
                    if (pCh == 0 || pCh == CLOSE_BRACKET) break
                    p++
                }
                if (pCh == 0) return ABORT_ALL
                val len = p - s - 1
                if (len < 0 || pc(p - 1) != COLON) {
                    // No ":]": the "[" is an ordinary member and scanning resumes right after it.
                    p = s - 2
                    pCh = OPEN_BRACKET
                    if (tCh == pCh) matched = true
                } else {
                    val hit = classHit(s, len, tCh) ?: return ABORT_ALL
                    if (hit) matched = true
                    pCh = 0
                }
            } else if (tCh == pCh) {
                matched = true
            }
            prevCh = pCh
            p++
            pCh = pc(p)
            if (pCh == CLOSE_BRACKET) break
        }
        val accepted = !(matched == negated || (pathname && tCh == SLASH))
        return (p shl 1) or (if (accepted) 1 else 0)
    }

    /** Whether the named POSIX class (bytes `s until s + len` of the pattern) contains [c]; null for an unknown name. */
    @Suppress("CyclomaticComplexMethod")
    private fun classHit(
        s: Int,
        len: Int,
        c: Int,
    ): Boolean? {
        val name = String(pat, s, len, Charsets.ISO_8859_1)
        return when (name) {
            "alnum" -> isAlpha(c) || isDigit(c)
            "alpha" -> isAlpha(c)
            "blank" -> c == SPACE || c == TAB
            "cntrl" -> c < SPACE || c == DEL
            "digit" -> isDigit(c)
            "graph" -> c > SPACE && c < DEL
            "lower" -> isLower(c)
            "print" -> c >= SPACE && c < DEL
            "punct" -> c > SPACE && c < DEL && !isAlpha(c) && !isDigit(c)
            // git's own isspace: tab, newline, carriage return, space (not VT or FF).
            "space" -> c == SPACE || c == TAB || c == LF || c == CR
            "upper" -> isUpper(c) || (fold && isLower(c))
            "xdigit" -> isDigit(c) || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code
            else -> null
        }
    }

    companion object {
        const val MATCH = 0
        const val NOMATCH = 1
        const val ABORT_ALL = -1
        const val ABORT_TO_STARSTAR = -2

        private const val STAR = '*'.code
        private const val QUESTION = '?'.code
        private const val BACKSLASH = '\\'.code
        private const val OPEN_BRACKET = '['.code
        private const val CLOSE_BRACKET = ']'.code
        private const val SLASH = '/'.code
        private const val SLASH_BYTE = '/'.code.toByte()
        private const val CARET = '^'.code
        private const val BANG = '!'.code
        private const val DASH = '-'.code
        private const val COLON = ':'.code
        private const val SPACE = ' '.code
        private const val TAB = 9
        private const val LF = 10
        private const val CR = 13
        private const val DEL = 127
        private const val CASE_DELTA = 'a'.code - 'A'.code

        fun isUpper(c: Int): Boolean = c in 'A'.code..'Z'.code

        fun isLower(c: Int): Boolean = c in 'a'.code..'z'.code

        fun isAlpha(c: Int): Boolean = isUpper(c) || isLower(c)

        fun isDigit(c: Int): Boolean = c in '0'.code..'9'.code

        /** git's GIT_GLOB_SPECIAL: `*`, `?`, `[` and the backslash. */
        fun isGlobSpecial(c: Int): Boolean = c == STAR || c == QUESTION || c == OPEN_BRACKET || c == BACKSLASH

        /** ASCII-only lower-casing of a byte, as git's `tolower` on its sane ctype table does. */
        fun lowerAscii(c: Int): Int = if (isUpper(c)) c + CASE_DELTA else c

        /**
         * Whether `pat[pStart until pEnd]` matches `txt[tStart until tEnd]`. [pathname] is `WM_PATHNAME` (a
         * single `*` and `?` do not cross `/`); [fold] is `WM_CASEFOLD`.
         */
        @Suppress("LongParameterList")
        fun matches(
            pat: ByteArray,
            pStart: Int,
            pEnd: Int,
            txt: ByteArray,
            tStart: Int,
            tEnd: Int,
            pathname: Boolean,
            fold: Boolean,
        ): Boolean = Wildmatch(pat, pEnd, txt, tEnd, pathname, fold).dowild(pStart, tStart) == MATCH
    }
}
