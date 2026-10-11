package org.krost.unidrive.internxt

import org.junit.Assume.assumeTrue
import org.krost.unidrive.auth.CredentialProtectionException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacKeychainCredentialProtectorTest {
    private val protector = MacKeychainCredentialProtector()

    @Test
    fun `legacy plaintext is identified without native Keychain access`() {
        val decoded = protector.decode("profile", "legacy credentials")

        assertEquals("legacy credentials", decoded.plaintext)
        assertFalse(decoded.protected)
    }

    @Test
    fun `protected reference resolves exact credential revision`() {
        assumeMacOS()
        val profileId = testProfileId()
        try {
            val oldSecret = "credential-private-value-${UUID.randomUUID()}"
            val newSecret = "rotated-private-value-${UUID.randomUUID()}"
            val oldReference = protector.protect(profileId, oldSecret)
            val newReference = protector.protect(profileId, newSecret)

            assertTrue(oldReference.startsWith("unidrive-keychain-v1:"))
            assertFalse(oldReference.contains(oldSecret))
            assertEquals(oldSecret, protector.decode(profileId, oldReference).plaintext)
            assertEquals(newSecret, protector.decode(profileId, newReference).plaintext)

            protector.discard(profileId, oldReference)
            assertFailsWith<CredentialProtectionException> { protector.decode(profileId, oldReference) }
            assertEquals(newSecret, protector.decode(profileId, newReference).plaintext)
        } finally {
            protector.delete(profileId)
        }
    }

    @Test
    fun `delete removes every revision and is idempotent`() {
        assumeMacOS()
        val profileId = testProfileId()
        try {
            val references = listOf(protector.protect(profileId, "first"), protector.protect(profileId, "second"))

            protector.delete(profileId)
            protector.delete(profileId)

            references.forEach { reference ->
                assertFailsWith<CredentialProtectionException> { protector.decode(profileId, reference) }
            }
        } finally {
            protector.delete(profileId)
        }
    }

    @Test
    fun `malformed protected reference fails closed`() {
        assertFailsWith<CredentialProtectionException> {
            protector.decode("profile", "unidrive-keychain-v1:not-a-uuid")
        }
    }

    @Test
    fun `unsupported platform fails closed`() {
        if (isMacOS()) return

        assertFailsWith<CredentialProtectionException> {
            protector.protect("profile", "secret")
        }
    }

    private fun assumeMacOS() = assumeTrue("macOS Keychain integration test", isMacOS())

    private fun isMacOS(): Boolean = System.getProperty("os.name", "").contains("mac", ignoreCase = true)

    private fun testProfileId(): String = "/test/internxt/credentials/${UUID.randomUUID()}"
}
