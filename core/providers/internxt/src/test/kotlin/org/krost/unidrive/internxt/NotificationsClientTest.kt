package org.krost.unidrive.internxt

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Frame handling treats product identities shared by multiple installations and malformed
 * frames as wake hints. Transport reconnection is covered separately.
 */
class NotificationsClientTest {
    private val ownClientName = "unidrive"

    private fun newClient(callback: () -> Unit): NotificationsClient =
        NotificationsClient(
            notificationsUrl = "https://example.invalid/socket.io",
            tokenSupplier = { error("must not be called in pure-handler tests") },
            onRemoteChange = callback,
        )

    @Test
    fun `frame from another unidrive installation triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        val frame =
            JSONObject().apply {
                put("clientId", ownClientName)
                put("event", "FILE_CREATED")
                put("email", "test@example.com")
            }
        client.handleIncomingFrame(frame)
        assertEquals(1, hits.get(), "shared product identity must not suppress another device")
    }

    @Test
    fun `remote-origin frame from drive-web triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        val frame =
            JSONObject().apply {
                put("clientId", "drive-web")
                put("event", "FILE_CREATED")
                put("email", "test@example.com")
            }
        client.handleIncomingFrame(frame)
        assertEquals(1, hits.get(), "remote-origin frame must trigger exactly one wake hint")
    }

    @Test
    fun `frame from another desktop variant triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        val frame =
            JSONObject().apply {
                put("clientId", "drive-desktop-windows")
                put("event", "FOLDER_UPDATED")
            }
        client.handleIncomingFrame(frame)
        assertEquals(1, hits.get(), "non-self clientId must trigger a wake hint")
    }

    @Test
    fun `malformed frame with no clientId triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        val frame =
            JSONObject().apply {
                put("event", "MYSTERY_EVENT")
                put("payload", "{}")
            }
        client.handleIncomingFrame(frame)
        assertEquals(
            1,
            hits.get(),
            "schema-incomplete frames mirror drive-desktop's fallthrough: treat as remote, walk delta",
        )
    }

    @Test
    fun `null frame triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        client.handleIncomingFrame(null)
        assertEquals(1, hits.get(), "null payload still walks the delta — safer than silent drop")
    }

    @Test
    fun `string frame triggers a wake hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        client.handleIncomingFrame("not-a-json-object")
        assertEquals(1, hits.get(), "non-object frame must trigger a wake hint")
    }

    @Test
    fun `map frame with shared client name triggers a wake hint`() {
        // Future-proof path: if socket.io's payload type ever shifts to a Map,
        // shared product identity must still trigger a hint.
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        client.handleIncomingFrame(mapOf("clientId" to ownClientName, "event" to "FILE_CREATED"))
        assertEquals(1, hits.get())
    }

    @Test
    fun `map frame with non-matching clientId triggers a hint`() {
        val hits = AtomicInteger(0)
        val client = newClient { hits.incrementAndGet() }
        client.handleIncomingFrame(mapOf("clientId" to "drive-web", "event" to "FILE_CREATED"))
        assertEquals(1, hits.get())
    }

    @Test
    fun `extractClientId pulls value from JSONObject`() {
        val frame = JSONObject().apply { put("clientId", "drive-web") }
        assertEquals("drive-web", NotificationsClient.extractClientId(frame))
    }

    @Test
    fun `extractClientId returns null when field is absent`() {
        val frame = JSONObject().apply { put("event", "FILE_CREATED") }
        assertNull(NotificationsClient.extractClientId(frame))
    }

    @Test
    fun `extractClientId returns null when field is JSON null`() {
        val frame = JSONObject().apply { put("clientId", JSONObject.NULL) }
        assertNull(NotificationsClient.extractClientId(frame))
    }

    @Test
    fun `extractClientId from Map returns value`() {
        assertEquals(
            "drive-web",
            NotificationsClient.extractClientId(mapOf("clientId" to "drive-web")),
        )
    }

    @Test
    fun `extractClientId from null is null`() {
        assertNull(NotificationsClient.extractClientId(null))
    }

    @Test
    fun `callback exception does not propagate`() {
        // A misbehaving engine callback must not kill the socket.io worker
        // thread — that would silently disable WS reconnection.
        val client =
            newClient {
                throw RuntimeException("simulated downstream failure")
            }
        // Should not throw.
        client.handleIncomingFrame(JSONObject().apply { put("clientId", "drive-web") })
    }
}
