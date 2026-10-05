package org.krost.unidrive.sync

import java.time.Instant

/**
 * #552: state.db stores its timestamps as `Instant.toString()` text, and every row read parsed three of
 * them with `Instant.parse`, which runs the whole `DateTimeFormatter` machinery (a parse context, boxed
 * fields) for a fixed shape. Loading the rows of a drive with hundreds of thousands of entries made that
 * a visible share of the allocations.
 *
 * [parse] reads the shape `Instant.toString()` writes, `yyyy-MM-ddTHH:mm:ss[.f{1,9}]Z`, by hand and hands
 * everything else (offsets, leap seconds, other years, lower case, garbage) to `Instant.parse`, so the
 * result and the exception for a bad value are the ones the caller always got.
 */
internal object IsoInstants {
    fun parse(text: String): Instant = parseFixedShape(text) ?: Instant.parse(text)

    private fun parseFixedShape(s: String): Instant? {
        val n = s.length
        if (n < 20 || s[n - 1] != 'Z') return null
        if (s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') return null
        val year = number(s, 0, 4)
        val month = number(s, 5, 2)
        val day = number(s, 8, 2)
        val hour = number(s, 11, 2)
        val minute = number(s, 14, 2)
        val second = number(s, 17, 2)
        if (year < 0 || month !in 1..12 || day < 1 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        if (day > daysInMonth(year, month)) return null
        var nanos = 0L
        if (n != 20) {
            val fraction = n - 21
            if (s[19] != '.' || fraction !in 1..9) return null
            for (i in 20 until n - 1) {
                val d = s[i] - '0'
                if (d !in 0..9) return null
                nanos = nanos * 10 + d
            }
            repeat(9 - fraction) { nanos *= 10 }
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
        return Instant.ofEpochSecond(seconds, nanos)
    }

    // [length] digits at [from], or -1 when one of them is not a digit.
    private fun number(
        s: String,
        from: Int,
        length: Int,
    ): Int {
        var value = 0
        for (i in from until from + length) {
            val d = s[i] - '0'
            if (d !in 0..9) return -1
            value = value * 10 + d
        }
        return value
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    // Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's days_from_civil).
    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
