package org.krost.unidrive.cli

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupCommandTest {
    // ── defaultBackupTarget hostname logic ────────────────────────────────────

    @Test
    fun `defaultBackupTarget returns path with hostname prefix`() {
        val target = defaultBackupTarget()
        assertTrue(target.startsWith("/backups/"))
        val expectedHostname =
            try {
                InetAddress.getLocalHost().hostName
            } catch (_: Exception) {
                "unknown"
            }
        assertEquals("/backups/$expectedHostname", target)
    }

    @Test
    fun `defaultBackupTarget does not return empty hostname segment`() {
        val target = defaultBackupTarget()
        // The path after /backups/ must be non-empty
        val segment = target.removePrefix("/backups/")
        assertTrue(segment.isNotEmpty(), "hostname segment must not be empty")
    }

    // ── parseSectionFields ────────────────────────────────────────────────────

    @Test
    fun `parseSectionFields extracts fields from backup section`() {
        val toml =
            """
            [general]
            poll_interval = 60

            [providers.my-backup]
              type = "s3"
              sync_direction = "upload"
              sync_root = "~/unidrive-backup-my-backup"
              remote_path = "/backups/myhost"
              file_versioning = true
              max_versions = 5
            """.trimIndent()

        val fields = parseSectionFields(toml)
        val backup = fields["my-backup"]!!
        assertEquals("s3", backup["type"])
        assertEquals("upload", backup["sync_direction"])
        assertEquals("/backups/myhost", backup["remote_path"])
        assertEquals("true", backup["file_versioning"])
        assertEquals("5", backup["max_versions"])
    }

    @Test
    fun `parseSectionFields handles multiple sections`() {
        val toml =
            """
            [general]

            [providers.work]
              type = "onedrive"
              sync_root = "~/OneDrive"

            [providers.backup-nas]
              type = "sftp"
              sync_direction = "upload"
              remote_path = "/backups/desktop"
            """.trimIndent()

        val fields = parseSectionFields(toml)
        assertEquals(2, fields.size)
        assertEquals("onedrive", fields["work"]!!["type"])
        assertEquals("upload", fields["backup-nas"]!!["sync_direction"])
        assertEquals("/backups/desktop", fields["backup-nas"]!!["remote_path"])
    }

    @Test
    fun `parseSectionFields returns empty map for no providers`() {
        val toml =
            """
            [general]
            poll_interval = 60
            """.trimIndent()

        val fields = parseSectionFields(toml)
        assertTrue(fields.isEmpty())
    }

    @Test
    fun `parseSectionFields ignores general section fields`() {
        val toml =
            """
            [general]
            sync_direction = "bidirectional"
            file_versioning = false

            [providers.test]
              type = "s3"
            """.trimIndent()

        val fields = parseSectionFields(toml)
        // general section should not appear
        assertTrue("general" !in fields)
        assertEquals(1, fields.size)
        assertEquals("s3", fields["test"]!!["type"])
    }
}
