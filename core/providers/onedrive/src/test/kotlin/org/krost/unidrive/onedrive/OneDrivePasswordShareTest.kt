package org.krost.unidrive.onedrive

import kotlinx.serialization.json.*
import kotlin.test.*

class OneDrivePasswordShareTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun buildShareBody(password: String? = null): JsonObject =
        buildJsonObject {
            put("type", "view")
            put("scope", "anonymous")
            put("expirationDateTime", "2026-01-01T00:00:00Z")
            if (password != null) put("password", password)
        }

    @Test
    fun `share body includes password when set`() {
        val body = buildShareBody(password = "secret123")
        assertTrue(body.containsKey("password"), "Body should contain password field")
        assertEquals("secret123", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `share body omits password when null`() {
        val body = buildShareBody(password = null)
        assertFalse(body.containsKey("password"), "Body should not contain password field")
    }
}
