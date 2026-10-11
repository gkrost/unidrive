package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialProtectionException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogoutCredentialProtectionTest {
    @Test
    fun `locked keyring failure preserves credential marker`() {
        val profileDir = Files.createTempDirectory("logout-protected-credentials")
        val marker = profileDir.resolve("credentials.json")
        Files.writeString(marker, "unidrive-secret-service-v1:opaque-version")
        try {
            assertFailsWith<CredentialProtectionException> {
                logoutAndDeleteLocalFiles(profileDir, keepState = false) {
                    throw CredentialProtectionException("keyring locked")
                }
            }

            assertTrue(Files.exists(marker))
            assertEquals("unidrive-secret-service-v1:opaque-version", Files.readString(marker))
        } finally {
            profileDir.toFile().deleteRecursively()
        }
    }
}
