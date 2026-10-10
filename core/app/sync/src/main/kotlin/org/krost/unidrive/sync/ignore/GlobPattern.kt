package org.krost.unidrive.sync.ignore

/**
 * One gitignore pattern, compiled for matching against UTF-8 bytes.
 *
 * Written from the `gitignore` documentation and glob(7), and checked against `git check-ignore` used as a black box
 * (the oracle corpus). The pattern becomes a list of tokens. Common shapes (a fixed-length pattern, `*.ext`, `name*`,
 * `pre*post`, a literal prefix before a trailing double star) are matched directly; everything else first checks
 * its literal head and tail, then runs as a small nondeterministic automaton over the text, one byte at a time, so
 * the cost is at most (text length x pattern length) and never exponential.
 *
 * Case-insensitive mode works on folded text: the caller lowers the ASCII letters of the text ([foldAscii]) and the
 * token byte sets are defined over such folded bytes. Observed git behaviour that this reproduces: an unescaped
 * letter matches either case, an escaped letter and a single bracket member compare with the folded text as written
 * (so `\A` and `[A]` match nothing), a bracket range also accepts the upper-case form of a lower-case letter, and
 * `[:upper:]` / `[:lower:]` both accept any ASCII letter.
 */
internal class GlobPattern private constructor(
    private val shape: Int,
    private val kinds: IntArray,
    private val sets: Array<BooleanArray>,
    /** Single-byte tokens before the first variable-length token. */
    private val head: Int,
    /** Single-byte tokens after the last variable-length token. */
    private val tail: Int,
) {
    /** True when the bytes `text[from until to]` match the whole pattern. */
    fun matches(
        text: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        val len = to - from
        return when (shape) {
            NEVER -> false
            FIXED -> len == kinds.size && fixed(text, from, 0, kinds.size)
            STAR_SHAPE ->
                len >= head + tail &&
                    fixed(text, from, 0, head) &&
                    fixed(text, to - tail, head + 1, tail) &&
                    noSlash(text, from + head, to - tail)
            ANY_SHAPE -> len >= head && fixed(text, from, 0, head)
            else ->
                len >= head + tail &&
                    fixed(text, from, 0, head) &&
                    fixed(text, to - tail, kinds.size - tail, tail) &&
                    automaton(text, from, to)
        }
    }

    private fun fixed(
        text: ByteArray,
        at: Int,
        firstToken: Int,
        count: Int,
    ): Boolean {
        for (k in 0 until count) {
            if (!sets[firstToken + k][text[at + k].toInt() and 0xFF]) return false
        }
        return true
    }

    private fun noSlash(
        text: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        for (i in from until to) if (text[i] == SLASH) return false
        return true
    }

    /**
     * Automaton states: one per token, two for a directory run, plus the accepting state. `stateKind`/`stateSet`
     * are built once in [compile].
     */
    private lateinit var stateKind: IntArray
    private lateinit var stateSet: Array<BooleanArray?>

    private fun automaton(
        text: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        val n = stateKind.size
        val accept = n - 1
        var cur = IntArray(n)
        var nxt = IntArray(n)
        val seen = IntArray(n)
        var stamp = 1
        var curSize = addClosure(0, cur, 0, seen, stamp)
        for (i in from until to) {
            if (curSize == 0) return false
            val b = text[i].toInt() and 0xFF
            stamp++
            var nxtSize = 0
            for (k in 0 until curSize) {
                val s = cur[k]
                when (stateKind[s]) {
                    S_ONE -> if (stateSet[s]!![b]) nxtSize = addClosure(s + 1, nxt, nxtSize, seen, stamp)
                    S_STAR -> if (stateSet[s]!![b]) nxtSize = addClosure(s, nxt, nxtSize, seen, stamp)
                    S_ANY -> nxtSize = addClosure(s, nxt, nxtSize, seen, stamp)
                    S_DIRS0 -> nxtSize = addClosure(s + 1, nxt, nxtSize, seen, stamp)
                    S_DIRS1 -> {
                        nxtSize = addClosure(s, nxt, nxtSize, seen, stamp)
                        if (b == SLASH_INT) nxtSize = addClosure(s + 1, nxt, nxtSize, seen, stamp)
                    }
                    // S_ACCEPT consumes nothing.
                }
            }
            val t = cur
            cur = nxt
            nxt = t
            curSize = nxtSize
        }
        for (k in 0 until curSize) if (cur[k] == accept) return true
        return false
    }

    /** Adds [state] and everything reachable from it without consuming a byte. */
    private fun addClosure(
        state: Int,
        into: IntArray,
        size: Int,
        seen: IntArray,
        stamp: Int,
    ): Int {
        var s = state
        var n = size
        while (true) {
            if (seen[s] == stamp) return n
            seen[s] = stamp
            into[n++] = s
            when (stateKind[s]) {
                S_STAR, S_ANY -> s += 1
                S_DIRS0 -> s += 2
                else -> return n
            }
        }
    }

    companion object {
        private const val SLASH: Byte = '/'.code.toByte()
        private const val SLASH_INT = '/'.code
        private const val BACKSLASH: Byte = '\\'.code.toByte()

        private const val NEVER = 0
        private const val FIXED = 1
        private const val STAR_SHAPE = 2
        private const val ANY_SHAPE = 3
        private const val GENERAL = 4

        // Token kinds.
        private const val T_ONE = 0
        private const val T_STAR = 1
        private const val T_ANY = 2
        private const val T_DIRS = 3

        // Automaton state kinds.
        private const val S_ONE = 0
        private const val S_STAR = 1
        private const val S_ANY = 2
        private const val S_DIRS0 = 3
        private const val S_DIRS1 = 4
        private const val S_ACCEPT = 5

        private val ALL = BooleanArray(256) { true }
        private val NOT_SLASH = BooleanArray(256) { it != SLASH_INT }

        private val never = GlobPattern(NEVER, IntArray(0), emptyArray(), 0, 0)

        /** Lowers the ASCII letters of [bytes] in place. */
        fun foldAscii(bytes: ByteArray): ByteArray {
            for (i in bytes.indices) {
                val c = bytes[i].toInt()
                if (c in 'A'.code..'Z'.code) bytes[i] = (c + 32).toByte()
            }
            return bytes
        }

        /**
         * Compiles [pattern] (already stripped of `!`, of the anchoring `/` and of the directory `/`). A pattern git
         * would never match (a trailing backslash, an unterminated bracket, an unknown character class) compiles to
         * one that matches nothing.
         *
         * @param leadingDirsAsBasename when true and the pattern is `**` + `/` + something that cannot contain a
         *   slash, return the pattern for that something instead and report it through [Compiled.basename]: matching
         *   it against the last component is the same thing and much cheaper.
         */
        fun compile(
            pattern: String,
            ignoreCase: Boolean,
            leadingDirsAsBasename: Boolean = false,
        ): Compiled {
            val tokens = tokenize(pattern.toByteArray(Charsets.UTF_8), ignoreCase) ?: return Compiled(never, false)
            var kinds = tokens.map { it.first }.toIntArray()
            var sets = tokens.map { it.second }.toTypedArray()
            var basename = false
            if (leadingDirsAsBasename && kinds.isNotEmpty() && kinds[0] == T_DIRS) {
                val rest = 1 until kinds.size
                val restCanSpanSlash = rest.any { kinds[it] == T_ANY || kinds[it] == T_DIRS || sets[it][SLASH_INT] }
                if (!restCanSpanSlash) {
                    kinds = kinds.copyOfRange(1, kinds.size)
                    sets = sets.copyOfRange(1, sets.size)
                    basename = true
                }
            }
            return Compiled(build(kinds, sets), basename)
        }

        class Compiled(
            val glob: GlobPattern,
            val basename: Boolean,
        )

        private fun build(
            kinds: IntArray,
            sets: Array<BooleanArray>,
        ): GlobPattern {
            val variable = kinds.indices.filter { kinds[it] != T_ONE }
            if (variable.isEmpty()) return GlobPattern(FIXED, kinds, sets, 0, 0)
            if (variable.size == 1) {
                val v = variable[0]
                if (kinds[v] == T_STAR) return GlobPattern(STAR_SHAPE, kinds, sets, v, kinds.size - v - 1)
                if (kinds[v] == T_ANY && v == kinds.size - 1) return GlobPattern(ANY_SHAPE, kinds, sets, v, 0)
            }
            val head = kinds.indexOfFirst { it != T_ONE }
            val tail = kinds.size - 1 - kinds.indexOfLast { it != T_ONE }
            val g = GlobPattern(GENERAL, kinds, sets, head, tail)
            val sk = ArrayList<Int>()
            val ss = ArrayList<BooleanArray?>()
            for (k in kinds.indices) {
                when (kinds[k]) {
                    T_ONE -> {
                        sk.add(S_ONE)
                        ss.add(sets[k])
                    }
                    T_STAR -> {
                        sk.add(S_STAR)
                        ss.add(sets[k])
                    }
                    T_ANY -> {
                        sk.add(S_ANY)
                        ss.add(null)
                    }
                    T_DIRS -> {
                        sk.add(S_DIRS0)
                        ss.add(null)
                        sk.add(S_DIRS1)
                        ss.add(null)
                    }
                }
            }
            sk.add(S_ACCEPT)
            ss.add(null)
            g.stateKind = sk.toIntArray()
            g.stateSet = ss.toTypedArray()
            return g
        }

        private fun literal(
            b: Int,
            fold: Boolean,
        ): BooleanArray {
            val set = BooleanArray(256)
            set[if (fold) lower(b) else b] = true
            return set
        }

        private fun lower(b: Int): Int = if (b in 'A'.code..'Z'.code) b + 32 else b

        private fun upper(b: Int): Int = if (b in 'a'.code..'z'.code) b - 32 else b

        private fun isLower(b: Int) = b in 'a'.code..'z'.code

        private fun isUpper(b: Int) = b in 'A'.code..'Z'.code

        /** The pattern as tokens (kind, byte set), or null for a pattern that can never match. */
        private fun tokenize(
            p: ByteArray,
            ignoreCase: Boolean,
        ): List<Pair<Int, BooleanArray>>? {
            val out = ArrayList<Pair<Int, BooleanArray>>()
            val n = p.size
            var i = 0
            // True at the start of the pattern and right after a slash: where a "**" is a whole component.
            var atComponentStart = true
            while (i < n) {
                val c = p[i].toInt() and 0xFF
                when (c) {
                    '*'.code -> {
                        var j = i
                        while (j < n && p[j] == '*'.code.toByte()) j++
                        val run = j - i
                        when {
                            // "**" at the end, or before an escaped slash (observed: "a/**\/b" matches "a/x/y/b" but
                            // not "a/b"): any run of bytes, slashes included.
                            run >= 2 && atComponentStart && (j == n || (p[j] == BACKSLASH && j + 1 < n && p[j + 1] == SLASH)) -> {
                                out.add(T_ANY to ALL)
                                i = j
                            }
                            // "**/": zero or more whole directories.
                            run >= 2 && atComponentStart && p[j] == SLASH -> {
                                out.add(T_DIRS to ALL)
                                i = j + 1
                            }
                            else -> {
                                if (out.isEmpty() || out.last().first != T_STAR) out.add(T_STAR to NOT_SLASH)
                                i = j
                            }
                        }
                        // After a directory run we are at the start of a component again.
                        atComponentStart = out.last().first == T_DIRS
                        continue
                    }
                    '?'.code -> {
                        out.add(T_ONE to NOT_SLASH)
                        i++
                    }
                    '['.code -> {
                        val (set, next) = bracket(p, i, ignoreCase) ?: return null
                        out.add(T_ONE to set)
                        i = next
                    }
                    '\\'.code -> {
                        if (i + 1 >= n) return null
                        val e = p[i + 1].toInt() and 0xFF
                        out.add(T_ONE to literal(e, fold = false))
                        i += 2
                        // Observed: an escaped slash also starts a component ("v\/**\/b" matches "v/a/c/b").
                        atComponentStart = e == SLASH_INT
                        continue
                    }
                    else -> {
                        out.add(T_ONE to literal(c, fold = ignoreCase))
                        i++
                        atComponentStart = c == SLASH_INT
                        continue
                    }
                }
                atComponentStart = false
            }
            return out
        }

        /**
         * A bracket expression starting at `p[start] == '['`, per glob(7): `!` or `^` negates, a `]` first is a
         * member, `a-z` is a range, `[:name:]` a class, a backslash escapes. Returns the byte set (never containing
         * the slash) and the index after the closing bracket, or null when the bracket is unterminated or names an
         * unknown class.
         */
        private fun bracket(
            p: ByteArray,
            start: Int,
            ignoreCase: Boolean,
        ): Pair<BooleanArray, Int>? {
            val n = p.size
            var j = start + 1
            var negated = false
            if (j < n && (p[j] == '!'.code.toByte() || p[j] == '^'.code.toByte())) {
                negated = true
                j++
            }
            val members = BooleanArray(256)
            val ranges = ArrayList<IntArray>()
            val classes = ArrayList<String>()
            var first = true
            while (true) {
                if (j >= n) return null
                val c = p[j].toInt() and 0xFF
                if (c == ']'.code && !first) {
                    j++
                    break
                }
                first = false
                if (c == '['.code && j + 1 < n && p[j + 1] == ':'.code.toByte()) {
                    val close = findClassEnd(p, j + 2)
                    if (close >= 0) {
                        val name = String(p, j + 2, close - (j + 2), Charsets.US_ASCII)
                        if (name !in CLASS_NAMES) return null
                        classes.add(name)
                        j = close + 2
                        continue
                    }
                }
                val lo: Int
                if (c == '\\'.code) {
                    if (j + 1 >= n) return null
                    lo = p[j + 1].toInt() and 0xFF
                    j += 2
                } else {
                    lo = c
                    j++
                }
                if (j + 1 < n && p[j] == '-'.code.toByte() && p[j + 1] != ']'.code.toByte()) {
                    j++
                    val hc = p[j].toInt() and 0xFF
                    val hi: Int
                    if (hc == '\\'.code) {
                        if (j + 1 >= n) return null
                        hi = p[j + 1].toInt() and 0xFF
                        j += 2
                    } else {
                        hi = hc
                        j++
                    }
                    // Observed: a reversed range ("z-a") is its first endpoint alone, compared like a single member.
                    if (lo <= hi) ranges.add(intArrayOf(lo, hi)) else members[lo] = true
                } else {
                    members[lo] = true
                }
            }
            val set = BooleanArray(256)
            for (t in 0 until 256) {
                var hit = members[t]
                if (!hit) {
                    for (r in ranges) {
                        if (t in r[0]..r[1] || (ignoreCase && isLower(t) && upper(t) in r[0]..r[1])) {
                            hit = true
                            break
                        }
                    }
                }
                if (!hit) hit = classes.any { inClass(it, t, ignoreCase) }
                set[t] = hit != negated
            }
            set[SLASH_INT] = false
            return set to j
        }

        /** Index of the ":" of the ":]" that closes a class name starting at [from], or -1. */
        private fun findClassEnd(
            p: ByteArray,
            from: Int,
        ): Int {
            var k = from
            while (k + 1 < p.size) {
                if (p[k] == ':'.code.toByte() && p[k + 1] == ']'.code.toByte()) return k
                if (p[k] == ']'.code.toByte()) return -1
                k++
            }
            return -1
        }

        private val CLASS_NAMES =
            setOf("alnum", "alpha", "blank", "cntrl", "digit", "graph", "lower", "print", "punct", "space", "upper", "xdigit")

        private fun inClass(
            name: String,
            b: Int,
            ignoreCase: Boolean,
        ): Boolean {
            val digit = b in '0'.code..'9'.code
            val alpha = isLower(b) || isUpper(b)
            return when (name) {
                "alnum" -> alpha || digit
                "alpha" -> alpha
                "blank" -> b == ' '.code || b == '\t'.code
                "cntrl" -> b < 0x20 || b == 0x7F
                "digit" -> digit
                "graph" -> b in 0x21..0x7E
                "lower" -> isLower(b) || (ignoreCase && isUpper(b))
                "print" -> b in 0x20..0x7E
                "punct" -> b in 0x21..0x7E && !alpha && !digit
                "space" -> b == ' '.code || b in 0x09..0x0D
                "upper" -> isUpper(b) || (ignoreCase && isLower(b))
                "xdigit" -> digit || b in 'a'.code..'f'.code || b in 'A'.code..'F'.code
                else -> false
            }
        }
    }
}
