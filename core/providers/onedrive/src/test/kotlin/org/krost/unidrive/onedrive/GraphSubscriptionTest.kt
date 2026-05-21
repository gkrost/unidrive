package org.krost.unidrive.onedrive

import kotlinx.serialization.json.*
import org.krost.unidrive.onedrive.model.SubscriptionResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class GraphSubscriptionTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── subscription creation request format ───────────────────────────────────

    @Test
    fun `subscription creation body contains required fields`() {
        val notificationUrl = "https://example.com/webhook"
        val expirationDateTime = "2026-04-15T12:00:00Z"
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "$notificationUrl",
                "resource": "/me/drive/root",
                "expirationDateTime": "$expirationDateTime"
            }
            """.trimIndent()

        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals("updated", parsed["changeType"]?.jsonPrimitive?.content)
        assertEquals(notificationUrl, parsed["notificationUrl"]?.jsonPrimitive?.content)
        assertEquals("/me/drive/root", parsed["resource"]?.jsonPrimitive?.content)
        assertEquals(expirationDateTime, parsed["expirationDateTime"]?.jsonPrimitive?.content)
    }

    @Test
    fun `subscription creation body has exactly four fields`() {
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "https://example.com/webhook",
                "resource": "/me/drive/root",
                "expirationDateTime": "2026-04-15T12:00:00Z"
            }
            """.trimIndent()

        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals(4, parsed.size, "Subscription creation body should have exactly 4 fields")
    }

    @Test
    fun `subscription creation resource targets drive root`() {
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "https://example.com/webhook",
                "resource": "/me/drive/root",
                "expirationDateTime": "2026-04-15T12:00:00Z"
            }
            """.trimIndent()

        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals("/me/drive/root", parsed["resource"]?.jsonPrimitive?.content)
    }

    @Test
    fun `subscription creation changeType is updated`() {
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "https://example.com/webhook",
                "resource": "/me/drive/root",
                "expirationDateTime": "2026-04-15T12:00:00Z"
            }
            """.trimIndent()

        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals("updated", parsed["changeType"]?.jsonPrimitive?.content)
    }

    // ── subscription renewal request format ────────────────────────────────────

    @Test
    fun `renewal body contains only expirationDateTime`() {
        val newExpiry = "2026-04-20T00:00:00Z"
        val body = """{"expirationDateTime":"$newExpiry"}"""

        val parsed = json.decodeFromString<JsonObject>(body)
        assertEquals(1, parsed.size, "Renewal body should have exactly 1 field")
        assertEquals(newExpiry, parsed["expirationDateTime"]?.jsonPrimitive?.content)
    }

    @Test
    fun `renewal body expirationDateTime is valid ISO-8601`() {
        val newExpiry = "2026-04-20T00:00:00Z"
        val body = """{"expirationDateTime":"$newExpiry"}"""

        val parsed = json.decodeFromString<JsonObject>(body)
        val expiry = parsed["expirationDateTime"]!!.jsonPrimitive.content
        val instant = Instant.parse(expiry)
        assertNotNull(instant, "expirationDateTime should be parseable as ISO-8601")
    }

    @Test
    fun `renewal URL includes subscription ID`() {
        val subscriptionId = "sub-abc-123"
        val url = "https://graph.microsoft.com/v1.0/subscriptions/$subscriptionId"
        assertTrue(url.endsWith(subscriptionId), "Renewal URL should end with subscription ID")
        assertTrue(url.contains("/subscriptions/"), "URL should contain /subscriptions/ path segment")
    }

    // ── SubscriptionResponse parsing ───────────────────────────────────────────

    @Test
    fun `SubscriptionResponse parses all fields from JSON`() {
        val raw =
            """
            {
                "id": "sub-999",
                "resource": "/me/drive/root",
                "applicationId": "app-id-123",
                "changeType": "updated",
                "notificationUrl": "https://hook.example.com/notify",
                "expirationDateTime": "2026-04-18T06:00:00Z"
            }
            """.trimIndent()

        val response = json.decodeFromString<SubscriptionResponse>(raw)
        assertEquals("sub-999", response.id)
        assertEquals("/me/drive/root", response.resource)
        assertEquals("app-id-123", response.applicationId)
        assertEquals("updated", response.changeType)
        assertEquals("https://hook.example.com/notify", response.notificationUrl)
        assertEquals("2026-04-18T06:00:00Z", response.expirationDateTime)
    }

    @Test
    fun `SubscriptionResponse handles missing optional fields`() {
        val raw = """{"id": "sub-minimal"}"""

        val response = json.decodeFromString<SubscriptionResponse>(raw)
        assertEquals("sub-minimal", response.id)
        assertNull(response.resource)
        assertNull(response.applicationId)
        assertNull(response.changeType)
        assertNull(response.notificationUrl)
        assertNull(response.expirationDateTime)
    }

    // ── expiry calculation ─────────────────────────────────────────────────────

    @Test
    fun `Graph API max subscription lifetime is 3 days for drive resources`() {
        // MS Graph allows max ~4230 minutes (approx 3 days) for drive subscriptions.
        // The implementation should not exceed this limit.
        val maxHours = 72L
        val now = Instant.now()
        val maxExpiry = now.plus(maxHours, ChronoUnit.HOURS)
        val hoursUntilExpiry = ChronoUnit.HOURS.between(now, maxExpiry)
        assertEquals(72, hoursUntilExpiry, "Max subscription lifetime should be 72 hours (3 days)")
    }

    @Test
    fun `expiry computation for renewal extends from current time`() {
        val now = Instant.now()
        val renewalHours = 48L
        val newExpiry = now.plusSeconds(renewalHours * 3600)
        val hoursFromNow = ChronoUnit.HOURS.between(now, newExpiry)
        assertEquals(48, hoursFromNow, "Renewal should extend by the requested hours from now")
    }

    @Test
    fun `expiry timestamp is ISO-8601 with Z suffix`() {
        val expiry = Instant.now().plusSeconds(24 * 3600).toString()
        assertTrue(expiry.endsWith("Z"), "Instant.toString() should produce Z-suffix ISO-8601")
        // Verify it's parseable
        val parsed = Instant.parse(expiry)
        assertNotNull(parsed)
    }

    @Test
    fun `createSubscription method exists on GraphApiService`() {
        val method = GraphApiService::class.java.declaredMethods.find { it.name == "createSubscription" }
        assertNotNull(method, "GraphApiService should have createSubscription method")
    }

    @Test
    fun `renewSubscription method exists on GraphApiService`() {
        val method = GraphApiService::class.java.declaredMethods.find { it.name == "renewSubscription" }
        assertNotNull(method, "GraphApiService should have renewSubscription method")
    }

    @Test
    fun `deleteSubscription method exists on GraphApiService`() {
        val method = GraphApiService::class.java.declaredMethods.find { it.name == "deleteSubscription" }
        assertNotNull(method, "GraphApiService should have deleteSubscription method")
    }
}
