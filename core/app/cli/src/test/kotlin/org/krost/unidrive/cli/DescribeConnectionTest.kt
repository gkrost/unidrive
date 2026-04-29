package org.krost.unidrive.cli

import org.krost.unidrive.onedrive.OneDriveProviderFactory
import org.krost.unidrive.rclone.RcloneProviderFactory
import org.krost.unidrive.s3.S3ProviderFactory
import org.krost.unidrive.sftp.SftpProviderFactory
import org.krost.unidrive.webdav.WebDavProviderFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class DescribeConnectionTest {
    // ── S3 ────────────────────────────────────────────────────────────────────

    @Test
    fun `s3 describes bucket, region, masked key`() {
        val factory = S3ProviderFactory()
        val props =
            mapOf(
                "bucket" to "my-bucket",
                "region" to "eu-central-1",
                "endpoint" to "https://s3.amazonaws.com",
                "access_key_id" to "AKIAIOSFODNN7EXAMPLE",
                "secret_access_key" to "secret",
            )
        val desc = factory.describeConnection(props, Files.createTempDirectory("s3"))
        assertTrue(desc.isNotBlank())
        assertTrue("bucket=my-bucket" in desc)
        assertTrue("region=eu-central-1" in desc)
        assertTrue("key=AKIA..." in desc)
        // Secret key must NOT appear
        assertTrue("secret" !in desc)
    }

    @Test
    fun `s3 returns non-blank even with empty properties`() {
        val factory = S3ProviderFactory()
        val desc = factory.describeConnection(emptyMap(), Files.createTempDirectory("s3"))
        assertTrue(desc.isNotBlank())
    }

    // ── SFTP ──────────────────────────────────────────────────────────────────

    @Test
    fun `sftp describes host, port, user, identity`() {
        val factory = SftpProviderFactory()
        val props =
            mapOf(
                "host" to "server.example.com",
                "port" to "2222",
                "user" to "alice",
                "identity" to "/home/alice/.ssh/id_ed25519",
            )
        val desc = factory.describeConnection(props, Files.createTempDirectory("sftp"))
        assertTrue(desc.isNotBlank())
        assertTrue("alice@server.example.com:2222" in desc)
        assertTrue("key=/home/alice/.ssh/id_ed25519" in desc)
    }

    @Test
    fun `sftp returns non-blank with minimal properties`() {
        val factory = SftpProviderFactory()
        val desc = factory.describeConnection(emptyMap(), Files.createTempDirectory("sftp"))
        assertTrue(desc.isNotBlank())
    }

    // ── WebDAV ────────────────────────────────────────────────────────────────

    @Test
    fun `webdav describes url, user, masked password`() {
        val factory = WebDavProviderFactory()
        val props =
            mapOf(
                "url" to "https://nextcloud.example.com/remote.php/dav/files/alice",
                "user" to "alice",
                "password" to "supersecret",
            )
        val desc = factory.describeConnection(props, Files.createTempDirectory("webdav"))
        assertTrue(desc.isNotBlank())
        assertTrue("nextcloud.example.com" in desc)
        assertTrue("user=alice" in desc)
        assertTrue("password=****" in desc)
        // Actual password must NOT appear
        assertTrue("supersecret" !in desc)
    }

    @Test
    fun `webdav returns non-blank with empty properties`() {
        val factory = WebDavProviderFactory()
        val desc = factory.describeConnection(emptyMap(), Files.createTempDirectory("webdav"))
        assertTrue(desc.isNotBlank())
    }

    // ── Rclone ────────────────────────────────────────────────────────────────

    @Test
    fun `rclone describes remote, path, custom binary`() {
        val factory = RcloneProviderFactory()
        val props =
            mapOf(
                "rclone_remote" to "gdrive:",
                "rclone_path" to "/backups",
                "rclone_binary" to "/usr/local/bin/rclone",
            )
        val desc = factory.describeConnection(props, Files.createTempDirectory("rclone"))
        assertTrue(desc.isNotBlank())
        assertTrue("remote=gdrive:" in desc)
        assertTrue("path=/backups" in desc)
        assertTrue("binary=/usr/local/bin/rclone" in desc)
    }

    @Test
    fun `rclone omits default binary`() {
        val factory = RcloneProviderFactory()
        val props = mapOf("rclone_remote" to "gdrive:", "rclone_binary" to "rclone")
        val desc = factory.describeConnection(props, Files.createTempDirectory("rclone"))
        assertTrue("binary=" !in desc)
    }

    @Test
    fun `rclone returns non-blank with empty properties`() {
        val factory = RcloneProviderFactory()
        val desc = factory.describeConnection(emptyMap(), Files.createTempDirectory("rclone"))
        assertTrue(desc.isNotBlank())
    }

    // ── OneDrive ──────────────────────────────────────────────────────────────

    @Test
    fun `onedrive returns non-blank when no token file`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val desc = factory.describeConnection(emptyMap(), dir)
        assertTrue(desc.isNotBlank())
        assertTrue("no token" in desc)
    }

    @Test
    fun `onedrive shows expiry when valid token exists`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() + 3600_000
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"header.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"preferred_username":"user@example.com"}""".toByteArray(),
            )}.sig","tokenType":"Bearer","expiresAt":$expiresAt,"refreshToken":"refresh"}""",
        )
        val desc = factory.describeConnection(emptyMap(), dir)
        assertTrue(desc.isNotBlank())
        assertTrue("user@example.com" in desc)
        assertTrue("expires in" in desc)
    }

    @Test
    fun `onedrive shows expired when token is past expiry`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        val expiresAt = System.currentTimeMillis() - 3600_000
        Files.writeString(
            dir.resolve("token.json"),
            """{"accessToken":"a.b.c","tokenType":"Bearer","expiresAt":$expiresAt,"refreshToken":"refresh"}""",
        )
        val desc = factory.describeConnection(emptyMap(), dir)
        assertTrue(desc.isNotBlank())
        assertTrue("expired" in desc)
    }

    @Test
    fun `onedrive handles corrupt token file gracefully`() {
        val factory = OneDriveProviderFactory()
        val dir = Files.createTempDirectory("onedrive")
        Files.writeString(dir.resolve("token.json"), "not-valid-json")
        val desc = factory.describeConnection(emptyMap(), dir)
        assertTrue(desc.isNotBlank())
        assertTrue("unreadable" in desc)
    }
}
