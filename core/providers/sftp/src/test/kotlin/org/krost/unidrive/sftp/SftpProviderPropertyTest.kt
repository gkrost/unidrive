package org.krost.unidrive.sftp

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Tests for [SftpProvider] properties and methods that do NOT require
 * a live SFTP connection (no SshServer needed).
 */
class SftpProviderPropertyTest {
    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    fun `id is sftp`() {
        val provider = SftpProvider(SftpConfig(host = "localhost"))
        assertEquals("sftp", provider.id)
    }

    @Test
    fun `displayName is SFTP`() {
        val provider = SftpProvider(SftpConfig(host = "localhost"))
        assertEquals("SFTP", provider.displayName)
    }

    // ── isAuthenticated ─────────────────────────────────────────────────────

    @Test
    fun `isAuthenticated is false initially`() {
        val provider = SftpProvider(SftpConfig(host = "localhost"))
        assertFalse(provider.isAuthenticated)
    }

    // ── canAuthenticate ─────────────────────────────────────────────────────

    @Test
    fun `canAuthenticate true when host and password are set`() {
        val provider =
            SftpProvider(
                SftpConfig(
                    host = "example.com",
                    password = "secret",
                    identityFile = null,
                ),
            )
        assertTrue(provider.canAuthenticate)
    }

    @Test
    fun `canAuthenticate true when host and identityFile are set`() {
        val provider =
            SftpProvider(
                SftpConfig(
                    host = "example.com",
                    identityFile =
                        java.nio.file.Paths
                            .get("/tmp/fake_key"),
                    password = null,
                ),
            )
        assertTrue(provider.canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when host is blank`() {
        val provider =
            SftpProvider(
                SftpConfig(
                    host = "",
                    password = "secret",
                    identityFile = null,
                ),
            )
        assertFalse(provider.canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when no credentials`() {
        val provider =
            SftpProvider(
                SftpConfig(
                    host = "example.com",
                    identityFile = null,
                    password = null,
                ),
            )
        assertFalse(provider.canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when host blank and no credentials`() {
        val provider =
            SftpProvider(
                SftpConfig(
                    host = "  ",
                    identityFile = null,
                    password = null,
                ),
            )
        assertFalse(provider.canAuthenticate)
    }

    // ── quota ────────────────────────────────────────────────────────────────

    @Test
    fun `quota returns zeros`() =
        runTest {
            val provider = SftpProvider(SftpConfig(host = "localhost"))
            val q = provider.quota()
            assertEquals(0L, q.total)
            assertEquals(0L, q.used)
            assertEquals(0L, q.remaining)
        }

    // ── close ────────────────────────────────────────────────────────────────

    @Test
    fun `close does not throw on fresh provider`() {
        val provider = SftpProvider(SftpConfig(host = "localhost"))
        provider.close()
        // No exception expected
    }

    @Test
    fun `close is idempotent`() {
        val provider = SftpProvider(SftpConfig(host = "localhost"))
        provider.close()
        provider.close()
        // No exception expected
    }

    // ── config exposure ─────────────────────────────────────────────────────

    @Test
    fun `config is accessible and matches constructor arg`() {
        val config = SftpConfig(host = "nas.local", port = 2222, username = "admin", remotePath = "/backup")
        val provider = SftpProvider(config)
        assertSame(config, provider.config)
        assertEquals("nas.local", provider.config.host)
        assertEquals(2222, provider.config.port)
        assertEquals("admin", provider.config.username)
        assertEquals("/backup", provider.config.remotePath)
    }
}
