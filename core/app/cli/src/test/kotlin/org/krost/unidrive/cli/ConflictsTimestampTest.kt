package org.krost.unidrive.cli

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictsTimestampTest {
    @Test
    fun `conflicts_timestamp_is_shown_in_the_local_zone_not_as_unlabelled_utc`() {
        assertEquals(
            "2026-10-09 15:58:09",
            formatConflictTimestamp("2026-10-09T13:58:09.123456Z", ZoneId.of("Europe/Berlin")),
        )
    }

    @Test
    fun `conflicts_timestamp_that_does_not_parse_falls_back_to_the_raw_prefix`() {
        assertEquals("2026-10-09 13:58:09", formatConflictTimestamp("2026-10-09T13:58:09 odd"))
    }
}
