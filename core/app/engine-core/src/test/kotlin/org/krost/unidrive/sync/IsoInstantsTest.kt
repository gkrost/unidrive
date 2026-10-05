package org.krost.unidrive.sync

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// #552: IsoInstants.parse must give what Instant.parse gives, for the text Instant.toString writes and for
// everything else (the shapes it hands on), including the exception of a bad value.
class IsoInstantsTest {
    private fun same(text: String) {
        val expected = runCatching { Instant.parse(text) }
        val actual = runCatching { IsoInstants.parse(text) }
        assertEquals(expected.getOrNull(), actual.getOrNull(), "result for '$text'")
        assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass, "exception for '$text'")
    }

    @Test
    fun `what Instant toString writes reads back unchanged for random instants of every fraction length`() {
        val random = Random(552)
        repeat(200_000) {
            val seconds = random.nextLong(-62_135_596_800L, 253_402_300_799L) // 0001-01-01 .. 9999-12-31
            val nanos =
                when (random.nextInt(4)) {
                    0 -> 0
                    1 -> random.nextInt(1000) * 1_000_000
                    2 -> random.nextInt(1_000_000) * 1000
                    else -> random.nextInt(1_000_000_000)
                }
            val instant = Instant.ofEpochSecond(seconds, nanos.toLong())
            assertEquals(instant, IsoInstants.parse(instant.toString()), instant.toString())
        }
    }

    @Test
    fun `calendar edges give the same instant as Instant parse`() {
        for (text in listOf(
            "1970-01-01T00:00:00Z", "1969-12-31T23:59:59Z", "2000-02-29T12:00:00Z", "1900-02-28T00:00:00Z", "2100-03-01T00:00:00Z",
            "2024-12-31T23:59:59.999999999Z", "0001-01-01T00:00:00Z", "0000-01-01T00:00:00Z", "9999-12-31T23:59:59Z",
            "2026-10-04T08:09:10.1Z", "2026-10-04T08:09:10.12Z", "2026-10-04T08:09:10.123456Z",
        )) same(text)
    }

    @Test
    fun `shapes it does not read itself go to Instant parse and answer the same`() {
        for (text in listOf(
            "2026-10-04T08:09:10+02:00", "2026-10-04T08:09:10-05:30", "2026-10-04t08:09:10z", "2026-06-30T23:59:60Z",
            "+12345-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "2026-10-04T08:09Z", "2026-10-04 08:09:10Z",
        )) same(text)
    }

    @Test
    fun `bad values fail the way Instant parse fails`() {
        for (text in listOf(
            "", "garbage", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2025-02-29T00:00:00Z", "2026-10-04T24:00:00Z",
            "2026-10-04T08:60:00Z", "2026-10-04T08:09:10.Z", "2026-10-04T08:09:10.1234567890Z", "2026-1x-04T08:09:10Z",
            "2026-10-04T08:09:10", "2026-10-04T08:09:1.5Z",
        )) same(text)
        assertFailsWith<java.time.format.DateTimeParseException> { IsoInstants.parse("2026-02-30T00:00:00Z") }
    }
}
