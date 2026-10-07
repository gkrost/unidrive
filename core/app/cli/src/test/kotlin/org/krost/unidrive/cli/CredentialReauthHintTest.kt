package org.krost.unidrive.cli

import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.internxt.InternxtProviderFactory
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Credentials on disk are not proof they still work. `profile list` used to show
 * a green tick for an Internxt profile whose JWT had expired months earlier,
 * while every network command then failed with "Not authenticated".
 * [credentialNeedsReauth] is the single decision both `profile list` and
 * `status --check-auth` use.
 */
class CredentialReauthHintTest {
    @Test
    fun `expired internxt jwt needs reauth even though credentials file exists`() {
        val dir = Files.createTempDirectory("reauth-hint")
        try {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1}""".toByteArray())
            Files.writeString(
                dir.resolve("credentials.json"),
                """{"jwt":"e30.$payload.sig","mnemonic":"m","rootFolderId":"r","email":"e"}""",
            )
            val factory = InternxtProviderFactory()
            assertTrue(factory.isAuthenticated(emptyMap(), dir), "precondition: file-existence check says authenticated")
            assertTrue(
                credentialNeedsReauth(factory.checkCredentialHealth(emptyMap(), dir)),
                "an expired JWT must not be reported as usable",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `token close to expiry is still usable`() {
        assertFalse(credentialNeedsReauth(CredentialHealth.ExpiresIn(5, "5h")))
        assertFalse(credentialNeedsReauth(CredentialHealth.Ok))
        assertTrue(credentialNeedsReauth(CredentialHealth.ExpiresIn(0, "JWT expired")))
        assertTrue(credentialNeedsReauth(CredentialHealth.Missing("No credentials file")))
        assertTrue(credentialNeedsReauth(CredentialHealth.Warning("token.json exists but could not be parsed")))
    }

    @Test
    fun `reauth hint names the profile not the provider type`() {
        assertEquals("run 'unidrive -p internxt_test auth'", reauthHint("internxt_test"))
    }
}
