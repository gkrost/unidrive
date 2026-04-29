package org.krost.unidrive.onedrive

import kotlinx.serialization.json.Json
import org.krost.unidrive.onedrive.model.Subscription
import org.krost.unidrive.onedrive.model.SubscriptionResponse
import kotlin.test.*

class OneDriveSubscriptionTest {
    @Test
    fun `CloudProvider has share method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        assertTrue(
            methods.any { it.name == "share" },
            "CloudProvider should have share method",
        )
    }

    @Test
    fun `CloudProvider has listShares method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        assertTrue(
            methods.any { it.name == "listShares" },
            "CloudProvider should have listShares method",
        )
    }

    @Test
    fun `CloudProvider has revokeShare method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        assertTrue(
            methods.any { it.name == "revokeShare" },
            "CloudProvider should have revokeShare method",
        )
    }

    @Test
    fun `CloudProvider has handleWebhookCallback method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        assertTrue(
            methods.any { it.name == "handleWebhookCallback" },
            "CloudProvider should have handleWebhookCallback method",
        )
    }

    @Test
    fun `SubscriptionResponse parses from JSON`() {
        val json =
            """
            {
                "id": "sub-123",
                "resource": "root",
                "applicationId": "app-456",
                "changeType": "updated",
                "notificationUrl": "https://example.com/webhook",
                "expirationDateTime": "2026-04-15T12:00:00Z"
            }
            """.trimIndent()

        val response =
            kotlinx.serialization.json.Json
                .decodeFromString<SubscriptionResponse>(json)
        assertEquals("sub-123", response.id)
        assertEquals("root", response.resource)
        assertEquals("https://example.com/webhook", response.notificationUrl)
    }

    @Test
    fun `SubscriptionResponse toSubscription extracts id`() {
        val response =
            SubscriptionResponse(
                id = "sub-abc",
                resource = "/drive/root",
                expirationDateTime = "2026-04-20T00:00:00Z",
            )
        val subscription = response.toSubscription()

        assertNotNull(subscription)
        assertEquals("sub-abc", subscription.id)
        assertEquals("/drive/root", subscription.resource)
    }

    @Test
    fun `SubscriptionResponse toSubscription returns null when id missing`() {
        val response =
            SubscriptionResponse(
                id = null,
                resource = "/drive/root",
                expirationDateTime = "2026-04-20T00:00:00Z",
            )
        val subscription = response.toSubscription()

        assertNull(subscription)
    }

    @Test
    fun `Subscription data class holds values`() {
        val sub =
            Subscription(
                id = "sub-xyz",
                resource = "/drive/root",
                expirationDateTime = "2026-04-18T12:34:56Z",
            )

        assertEquals("sub-xyz", sub.id)
        assertEquals("/drive/root", sub.resource)
        assertEquals("2026-04-18T12:34:56Z", sub.expirationDateTime)
    }
}
