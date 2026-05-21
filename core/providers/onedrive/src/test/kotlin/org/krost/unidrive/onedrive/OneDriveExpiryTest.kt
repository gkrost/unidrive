package org.krost.unidrive.onedrive

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class OneDriveExpiryTest {
    @Test
    fun `expirationDateTime is computed correctly for 24 hours`() {
        val before = Instant.now()
        val expiration = Instant.now().plusSeconds(24L * 3600)
        val after = Instant.now().plusSeconds(24L * 3600 + 1)

        assertTrue(expiration.isAfter(before), "Expiration should be in the future")
        assertTrue(expiration.isBefore(after), "Expiration should be roughly 24h from now")

        val hoursFromNow = ChronoUnit.HOURS.between(Instant.now(), expiration)
        assertTrue(hoursFromNow in 23..24, "Should be approximately 24 hours from now, was $hoursFromNow")
    }

    @Test
    fun `expirationDateTime is computed correctly for 1 hour`() {
        val now = Instant.now()
        val expiration = now.plusSeconds(1L * 3600)
        val hoursFromNow = ChronoUnit.HOURS.between(now, expiration)
        assertEquals(1, hoursFromNow, "Should be 1 hour from now")
    }

    @Test
    fun `expirationDateTime is computed correctly for 168 hours`() {
        val now = Instant.now()
        val expiration = now.plusSeconds(168L * 3600)
        val hoursFromNow = ChronoUnit.HOURS.between(now, expiration)
        assertEquals(168, hoursFromNow, "Should be 168 hours (7 days) from now")
    }

    @Test
    fun `sharing link JSON body includes expirationDateTime`() {
        val expiryHours = 48
        val expiration = Instant.now().plusSeconds(expiryHours.toLong() * 3600).toString()
        val body = """{"type":"view","scope":"anonymous","expirationDateTime":"$expiration"}"""

        assertTrue(body.contains("expirationDateTime"), "Body should contain expirationDateTime field")
        assertTrue(body.contains("view"), "Body should contain link type")
        assertTrue(body.contains("anonymous"), "Body should contain scope")
        // Verify the timestamp is parseable ISO-8601
        val extracted = Regex(""""expirationDateTime":"([^"]+)"""").find(body)!!.groupValues[1]
        val parsed = Instant.parse(extracted)
        val hoursFromNow = ChronoUnit.HOURS.between(Instant.now(), parsed)
        assertTrue(hoursFromNow in 47..48, "Expiry should be ~48h from now, was $hoursFromNow")
    }

    @Test
    fun `createSharingLink signature accepts expiryHours parameter`() {
        val method = GraphApiService::class.java.declaredMethods.find { it.name == "createSharingLink" }
        assertNotNull(method, "GraphApiService should have createSharingLink method")
        val paramNames = method.parameters.map { it.name }
        assertTrue(
            method.parameterCount >= 4,
            "createSharingLink should have at least 4 parameters (including continuation), has ${method.parameterCount}",
        )
    }
}
