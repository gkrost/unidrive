package org.krost.unidrive.cli

import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.onedrive.OneDriveProviderFactory
import org.krost.unidrive.rclone.RcloneProviderFactory
import org.krost.unidrive.s3.S3ProviderFactory
import org.krost.unidrive.sftp.SftpProviderFactory
import org.krost.unidrive.webdav.WebDavProviderFactory
import picocli.CommandLine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialHealthCheckTest {
    private val cmd = CommandLine(Main())

    // ── CLI flag registration ────────────────────────────────────────────────

    @Test
    fun `status command has --check-auth flag`() {
        val statusCmd = cmd.subcommands["status"]!!
        val options = statusCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--check-auth" in options)
    }

    // ── S3 credential health ─────────────────────────────────────────────────

    @Test
    fun `s3 returns Ok when all credentials present`() {
        val factory = S3ProviderFactory()
        val props = mapOf("bucket" to "my-bucket", "access_key_id" to "AKIA...", "secret_access_key" to "secret")
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("s3"))
        assertEquals(CredentialHealth.Ok, health)
    }

    @Test
    fun `s3 returns Missing when bucket absent`() {
        val factory = S3ProviderFactory()
        val props = mapOf("bucket" to null, "access_key_id" to "AKIA...", "secret_access_key" to "secret")
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("s3"))
        assertTrue(health is CredentialHealth.Missing)
        assertTrue("bucket" in (health as CredentialHealth.Missing).message)
    }

    @Test
    fun `s3 returns Missing listing all absent fields`() {
        val factory = S3ProviderFactory()
        val props = emptyMap<String, String?>()
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("s3"))
        assertTrue(health is CredentialHealth.Missing)
        val msg = (health as CredentialHealth.Missing).message
        assertTrue("bucket" in msg)
        assertTrue("access_key_id" in msg)
        assertTrue("secret_access_key" in msg)
    }

    // ── WebDAV credential health ─────────────────────────────────────────────

    @Test
    fun `webdav returns Ok when all credentials present`() {
        val factory = WebDavProviderFactory()
        val props = mapOf("url" to "https://example.com/dav", "user" to "alice", "password" to "pass")
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("webdav"))
        assertEquals(CredentialHealth.Ok, health)
    }

    @Test
    fun `webdav returns Missing when password absent`() {
        val factory = WebDavProviderFactory()
        val props = mapOf("url" to "https://example.com/dav", "user" to "alice", "password" to null)
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("webdav"))
        assertTrue(health is CredentialHealth.Missing)
        assertTrue("password" in (health as CredentialHealth.Missing).message)
    }

    // ── SFTP credential health ───────────────────────────────────────────────

    @Test
    fun `sftp returns Missing when host absent`() {
        val factory = SftpProviderFactory()
        val props = emptyMap<String, String?>()
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("sftp"))
        assertTrue(health is CredentialHealth.Missing)
        assertTrue("host" in (health as CredentialHealth.Missing).message)
    }

    @Test
    fun `sftp returns Warning when host present but no key or password`() {
        val factory = SftpProviderFactory()
        val dir = Files.createTempDirectory("sftp")
        // identity points to non-existent file, no password
        val props = mapOf("host" to "server.example.com", "identity" to dir.resolve("nonexistent_key").toString())
        val health = factory.checkCredentialHealth(props, dir)
        assertTrue(health is CredentialHealth.Warning)
    }

    @Test
    fun `sftp returns Ok when host and password present`() {
        val factory = SftpProviderFactory()
        val props = mapOf("host" to "server.example.com", "password" to "secret")
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("sftp"))
        assertEquals(CredentialHealth.Ok, health)
    }

    // ── Rclone credential health ─────────────────────────────────────────────

    @Test
    fun `rclone returns Ok when remote configured`() {
        val factory = RcloneProviderFactory()
        val props = mapOf("rclone_remote" to "gdrive:")
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("rclone"))
        assertEquals(CredentialHealth.Ok, health)
    }

    @Test
    fun `rclone returns Missing when remote absent`() {
        val factory = RcloneProviderFactory()
        val props = emptyMap<String, String?>()
        val health = factory.checkCredentialHealth(props, Files.createTempDirectory("rclone"))
        assertTrue(health is CredentialHealth.Missing)
    }

    // ── OneDrive credential health ───────────────────────────────────────────

    @Test
    fun `onedrive returns Missing when no token file`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertTrue(health is CredentialHealth.Missing)
    }

    @Test
    fun `onedrive returns Ok when valid token file with refresh token exists`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() + 25 * 3600_000 // 25+ hours
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"access","tokenType":"Bearer","expiresAt":$expiresAt,"refreshToken":"refresh"}""",
        )
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertEquals(CredentialHealth.Ok, health)
    }

    @Test
    fun `onedrive returns Ok when expired token has refresh token`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() - 3600_000
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"access","tokenType":"Bearer","expiresAt":$expiresAt,"refreshToken":"refresh"}""",
        )
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertEquals(CredentialHealth.Ok, health)
    }

    @Test
    fun `onedrive returns Warning when expired token has no refresh token`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() - 3600_000
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"access","tokenType":"Bearer","expiresAt":$expiresAt}""",
        )
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertTrue(health is CredentialHealth.Warning)
    }

    @Test
    fun `onedrive returns Warning when token file is corrupt`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        Files.writeString(dir.resolve("token.json"), "not-valid-json")
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertTrue(health is CredentialHealth.Warning)
    }

    @Test
    fun `onedrive returns ExpiresIn when token expires within 24h`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() + 6 * 3600_000 // 6 hours
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"access","tokenType":"Bearer","expiresAt":$expiresAt,"refreshToken":"refresh"}""",
        )
        val health = factory.checkCredentialHealth(emptyMap(), dir)
        assertTrue(health is CredentialHealth.ExpiresIn)
        val expiresIn = health as CredentialHealth.ExpiresIn
        assertTrue(expiresIn.hours <= 24)
    }
}
