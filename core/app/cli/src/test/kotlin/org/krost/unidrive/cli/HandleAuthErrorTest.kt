package org.krost.unidrive.cli

import org.krost.unidrive.AuthenticationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-210: `handleAuthError` used to discard `e.message` and `e.cause`, so a
 * host-key mismatch, a wrong password, and a missing `authorized_keys` entry
 * all rendered as the same opaque two-line output. Under `-v` the CLI now
 * appends `Cause:` and (when present) the fully-qualified class name of the
 * root cause.
 *
 * Tests drive [Main.renderAuthError] directly — calling [Main.handleAuthError]
 * itself is awkward because it invokes `System.exit`. The rendered output is
 * what matters for the ticket; the exit behaviour is unchanged.
 */
class HandleAuthErrorTest {
    private val providerId = "sftp"
    private val providerDisplayName = "SFTP"

    @Test
    fun `non-verbose output is unchanged and omits cause details`() {
        val cause = java.net.ConnectException("Connection refused")
        val e = AuthenticationException("Server key did not validate", cause)

        val rendered = Main().renderAuthError(e, providerId, providerDisplayName, verbose = false)

        assertTrue(
            rendered.contains("Not authenticated to SFTP."),
            "default output should keep the leading 'Not authenticated to <provider>.' line",
        )
        assertTrue(
            rendered.contains("Run: unidrive -p sftp auth"),
            "default output should keep the remediation hint",
        )
        assertFalse(
            rendered.contains("Cause:"),
            "default output must NOT leak cause details (regression guard)",
        )
        assertFalse(
            rendered.contains("java.net.ConnectException"),
            "default output must NOT leak the cause FQCN (regression guard)",
        )
    }

    @Test
    fun `verbose output surfaces message and cause FQCN`() {
        val cause = java.net.ConnectException("Connection refused")
        val e = AuthenticationException("Server key did not validate", cause)

        val rendered = Main().renderAuthError(e, providerId, providerDisplayName, verbose = true)

        // Existing two-line preamble still present.
        assertTrue(rendered.contains("Not authenticated to SFTP."))
        assertTrue(rendered.contains("Run: unidrive -p sftp auth"))

        // UD-210 additions.
        assertTrue(
            rendered.contains("Cause: Server key did not validate"),
            "verbose output should include the AuthenticationException message",
        )
        assertTrue(
            rendered.contains("java.net.ConnectException"),
            "verbose output should include the cause fully-qualified class name",
        )
        assertTrue(
            rendered.contains("Connection refused"),
            "verbose output should include the cause message",
        )
    }
}
