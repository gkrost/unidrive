package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.generateProfileToml
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.removeProfileSection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCommandTest {
    // ── generateProfileToml ──────────────────────────────────────────────────

    @Test
    fun `generateProfileToml produces valid S3 section`() {
        val toml =
            generateProfileToml(
                "s3",
                "hetzner-s3",
                "~/HetznerS3",
                mapOf(
                    "bucket" to "my-bucket",
                    "region" to "eu-central-1",
                    "endpoint" to "https://s3.eu-central-1.hetzner.com",
                    "access_key_id" to "AKIA123",
                    "secret_access_key" to "secret",
                ),
            )
        assertContains(toml, "[providers.hetzner-s3]")
        assertContains(toml, "type = \"s3\"")
        assertContains(toml, "sync_root = \"~/HetznerS3\"")
        assertContains(toml, "bucket = \"my-bucket\"")
        assertContains(toml, "access_key_id = \"AKIA123\"")
        assertContains(toml, "secret_access_key = \"secret\"")
    }

    @Test
    fun `generateProfileToml produces minimal OneDrive section`() {
        val toml = generateProfileToml("onedrive", "work", "~/OneDrive-Work", emptyMap())
        assertContains(toml, "[providers.work]")
        assertContains(toml, "type = \"onedrive\"")
        assertContains(toml, "sync_root = \"~/OneDrive-Work\"")
        // No credential fields
        assertTrue(toml.lines().count { it.contains("=") } == 2) // type + sync_root only
    }

    @Test
    fun `generateProfileToml skips blank credential values`() {
        val toml =
            generateProfileToml(
                "sftp",
                "nas",
                "~/NAS",
                mapOf(
                    "host" to "192.168.1.100",
                    "remote_path" to "",
                    "user" to "admin",
                ),
            )
        assertContains(toml, "host = \"192.168.1.100\"")
        assertContains(toml, "user = \"admin\"")
        // remote_path is blank → skipped
        assertTrue(toml.lines().none { it.startsWith("remote_path") })
    }

    @Test
    fun `generateProfileToml for WebDAV includes all fields`() {
        val toml =
            generateProfileToml(
                "webdav",
                "nextcloud",
                "~/Nextcloud",
                mapOf(
                    "url" to "https://cloud.example.com/dav",
                    "user" to "alice",
                    "password" to "s3cret",
                ),
            )
        assertContains(toml, "type = \"webdav\"")
        assertContains(toml, "url = \"https://cloud.example.com/dav\"")
        assertContains(toml, "user = \"alice\"")
        assertContains(toml, "password = \"s3cret\"")
    }

    @Test
    fun `generateProfileToml for rclone includes remote config`() {
        val toml =
            generateProfileToml(
                "rclone",
                "rclone-gdrive",
                "~/GDrive",
                mapOf(
                    "rclone_remote" to "gdrive",
                    "rclone_path" to "/backup",
                ),
            )
        assertContains(toml, "type = \"rclone\"")
        assertContains(toml, "rclone_remote = \"gdrive\"")
        assertContains(toml, "rclone_path = \"/backup\"")
    }

    @Test
    fun `generateProfileToml escapes special characters in values`() {
        val toml =
            generateProfileToml(
                "webdav",
                "test",
                "~/Test",
                mapOf(
                    "password" to """p@ss"word\with\slashes""",
                ),
            )
        assertContains(toml, """password = "p@ss\"word\\with\\slashes"""")
    }

    // ── removeProfileSection ─────────────────────────────────────────────────

    @Test
    fun `removeProfileSection excises target and preserves others`() {
        val config =
            """
            [general]
            poll_interval = 60

            [providers.work]
            type = "onedrive"
            sync_root = "~/OneDrive-Work"

            [providers.personal]
            type = "onedrive"
            sync_root = "~/OneDrive-Personal"

            [providers.nas]
            type = "sftp"
            host = "192.168.1.1"
            """.trimIndent().lines()

        val result = removeProfileSection(config, "personal")
        val joined = result.joinToString("\n")

        assertContains(joined, "[providers.work]")
        assertContains(joined, "[providers.nas]")
        assertTrue(joined.lines().none { it.contains("[providers.personal]") })
        assertTrue(joined.lines().none { it.contains("OneDrive-Personal") })
    }

    @Test
    fun `removeProfileSection handles last section without trailing bracket`() {
        val config =
            """
            [general]

            [providers.only]
            type = "s3"
            bucket = "test"
            """.trimIndent().lines()

        val result = removeProfileSection(config, "only")
        val joined = result.joinToString("\n")

        assertContains(joined, "[general]")
        assertTrue(result.none { it.contains("[providers.only]") })
        assertTrue(result.none { it.contains("bucket") })
    }

    @Test
    fun `removeProfileSection on nonexistent profile returns input unchanged`() {
        val config = listOf("[general]", "", "[providers.foo]", "type = \"s3\"")
        val result = removeProfileSection(config, "nonexistent")
        assertEquals(config, result)
    }

    @Test
    fun `removeProfileSection removes preceding blank lines`() {
        val config =
            listOf(
                "[general]",
                "",
                "",
                "[providers.remove-me]",
                "type = \"onedrive\"",
                "",
                "[providers.keep]",
                "type = \"s3\"",
            )
        val result = removeProfileSection(config, "remove-me")
        // Should not have double blank lines before [providers.keep]
        val joined = result.joinToString("\n")
        assertContains(joined, "[general]")
        assertContains(joined, "[providers.keep]")
        assertTrue(result.none { it.contains("remove-me") })
    }

    // ── Profile name validation ────────────────────────────────────────────

    @Test
    fun `isValidProfileName accepts valid names`() {
        assertTrue(isValidProfileName("onedrive"))
        assertTrue(isValidProfileName("my-s3"))
        assertTrue(isValidProfileName("hetzner_s3"))
        assertTrue(isValidProfileName("OneDrive-Work2"))
    }

    @Test
    fun `isValidProfileName rejects invalid names`() {
        assertFalse(isValidProfileName("my.s3")) // dots break TOML
        assertFalse(isValidProfileName("my s3")) // spaces
        assertFalse(isValidProfileName("[bad]")) // brackets
        assertFalse(isValidProfileName("")) // empty
        assertFalse(isValidProfileName("path/name")) // slashes
    }

    // ── Duplicate detection ──────────────────────────────────────────────────

    @Test
    fun `detectDuplicateSyncRoots catches duplicate`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [general]
                [providers.a]
                type = "onedrive"
                sync_root = "~/SameRoot"
                [providers.b]
                type = "s3"
                sync_root = "~/SameRoot"
                """.trimIndent(),
            )
        val err = SyncConfig.detectDuplicateSyncRoots(raw)
        assertNotNull(err)
        assertContains(err, "SameRoot")
    }

    @Test
    fun `detectDuplicateSyncRoots allows different roots`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [general]
                [providers.a]
                type = "onedrive"
                sync_root = "~/OneDrive"
                [providers.b]
                type = "s3"
                sync_root = "~/S3"
                """.trimIndent(),
            )
        assertNull(SyncConfig.detectDuplicateSyncRoots(raw))
    }
}
