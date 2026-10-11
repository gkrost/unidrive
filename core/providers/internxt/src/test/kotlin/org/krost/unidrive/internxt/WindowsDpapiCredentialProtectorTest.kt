package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowsDpapiCredentialProtectorTest {
    private val protector = WindowsDpapiCredentialProtector()

    @Test
    fun `DPAPI protects and restores a versioned credential marker for the current user`() {
        assumeTrue("DPAPI integration requires Windows", System.getProperty("os.name").lowercase().contains("windows"))

        val plain = "{\"accessToken\":\"sensitive-token\",\"mnemonic\":\"secret words\"}"
        val marker = protector.protect("profile-credentials", plain)

        assertTrue(marker.startsWith("unidrive-dpapi-v1:"))
        assertNotEquals(plain, marker)
        assertEquals(plain, protector.decode("profile-credentials", marker).plaintext)
        assertFailsWith<CredentialProtectionException> {
            protector.decode("different-profile", marker)
        }
    }

    @Test
    fun `malformed DPAPI marker fails closed`() {
        assumeTrue("DPAPI integration requires Windows", System.getProperty("os.name").lowercase().contains("windows"))

        assertFailsWith<CredentialProtectionException> {
            protector.decode("profile-credentials", "unidrive-dpapi-v1:not-base64!")
        }
    }
}
