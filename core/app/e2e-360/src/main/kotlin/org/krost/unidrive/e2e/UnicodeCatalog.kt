package org.krost.unidrive.e2e

import java.lang.Character

object UnicodeCatalog {

    /**
     * Returns all "graphic" Unicode codepoints: U+0020 to U+10FFFF, excluding
     * surrogates, noncharacters, undefined code points, and control/unassigned/
     * private-use categories.
     */
    fun allGraphicCodepoints(): List<Int> {
        val result = mutableListOf<Int>()
        for (cp in 0x0020..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue          // surrogates
            if (isNoncharacter(cp)) continue
            val type = Character.getType(cp)
            if (type == Character.CONTROL.toInt()) continue
            if (type == Character.UNASSIGNED.toInt()) continue
            if (type == Character.PRIVATE_USE.toInt()) continue
            result += cp
        }
        return result
    }

    /** Returns true if the codepoint is a Unicode noncharacter. */
    fun isNoncharacter(cp: Int): Boolean {
        if (cp in 0xFDD0..0xFDEF) return true
        val low = cp and 0xFFFF
        return low == 0xFFFE || low == 0xFFFF
    }

    /**
     * Returns a random sample of [count] codepoints drawn without replacement
     * from [allGraphicCodepoints].
     */
    fun sample(count: Int, seed: Long): List<Int> {
        val all = allGraphicCodepoints()
        val rng = java.util.Random(seed)
        val indices = (all.indices).toMutableList()
        repeat(count) { i ->
            val j = i + rng.nextInt(indices.size - i)
            val tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp
        }
        return indices.take(count).map { all[it] }
    }
}
