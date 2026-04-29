package org.krost.unidrive.rclone

import kotlin.test.*

class RcloneEntryParsingTest {
    @Test
    fun `parse file entry with hashes`() {
        val json = """[{
            "Path": "docs/report.pdf",
            "Name": "report.pdf",
            "Size": 512000,
            "MimeType": "application/pdf",
            "ModTime": "2025-03-10T14:00:00.000000000Z",
            "IsDir": false,
            "Hashes": {"SHA-1": "da39a3ee5e6b", "MD5": "d41d8cd98f00"},
            "ID": "abc123"
        }]"""

        val entries = RcloneCliService.parseEntries(json)
        assertEquals(1, entries.size)

        val entry = entries[0]
        assertEquals("docs/report.pdf", entry.path)
        assertEquals("report.pdf", entry.name)
        assertEquals(512_000L, entry.size)
        assertEquals("application/pdf", entry.mimeType)
        assertFalse(entry.isDir)
        assertEquals(mapOf("SHA-1" to "da39a3ee5e6b", "MD5" to "d41d8cd98f00"), entry.hashes)
        assertEquals("abc123", entry.id)
    }

    @Test
    fun `parse folder entry without hashes`() {
        val json = """[{
            "Path": "docs",
            "Name": "docs",
            "Size": 0,
            "ModTime": "2025-03-10T13:00:00Z",
            "IsDir": true
        }]"""

        val entries = RcloneCliService.parseEntries(json)
        assertEquals(1, entries.size)

        val entry = entries[0]
        assertEquals("docs", entry.path)
        assertTrue(entry.isDir)
        assertEquals(0L, entry.size)
        assertNull(entry.hashes)
        assertNull(entry.mimeType)
        assertNull(entry.id)
    }

    @Test
    fun `parse empty listing`() {
        val entries = RcloneCliService.parseEntries("[]")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `toCloudItem maps fields correctly with base path`() {
        val entry =
            RcloneEntry(
                path = "docs/report.pdf",
                name = "report.pdf",
                size = 512_000L,
                mimeType = "application/pdf",
                modTime = "2025-03-10T14:00:00Z",
                isDir = false,
                hashes = mapOf("SHA-1" to "da39a3ee5e6b"),
                id = "abc123",
            )

        // basePath is ignored — rclone paths are already relative to the remote root
        val item = RcloneCliService.toCloudItem(entry, "subfolder")
        assertEquals("/docs/report.pdf", item.id)
        assertEquals("/docs/report.pdf", item.path)
        assertEquals("report.pdf", item.name)
        assertEquals(512_000L, item.size)
        assertFalse(item.isFolder)
        assertEquals("da39a3ee5e6b", item.hash)
        assertFalse(item.deleted)
    }

    @Test
    fun `toCloudItem maps fields correctly without base path`() {
        val entry =
            RcloneEntry(
                path = "report.pdf",
                name = "report.pdf",
                size = 100L,
                modTime = "2025-01-01T00:00:00Z",
                isDir = false,
            )

        val item = RcloneCliService.toCloudItem(entry, "")
        assertEquals("/report.pdf", item.id)
        assertEquals("/report.pdf", item.path)
    }

    @Test
    fun `toCloudItem uses first hash value`() {
        val entry =
            RcloneEntry(
                path = "file.txt",
                name = "file.txt",
                size = 10L,
                modTime = "2025-01-01T00:00:00Z",
                isDir = false,
                hashes = mapOf("SHA-1" to "first", "MD5" to "second"),
            )

        val item = RcloneCliService.toCloudItem(entry, "")
        assertEquals("first", item.hash)
    }

    @Test
    fun `toCloudItem with null hashes sets hash to null`() {
        val entry =
            RcloneEntry(
                path = "file.txt",
                name = "file.txt",
                size = 10L,
                modTime = "2025-01-01T00:00:00Z",
                isDir = false,
                hashes = null,
            )

        val item = RcloneCliService.toCloudItem(entry, "")
        assertNull(item.hash)
    }

    @Test
    fun `sanitizePath strips leading and trailing slashes`() {
        assertEquals("docs/file.txt", RcloneCliService.sanitizePath("/docs/file.txt/"))
    }

    @Test
    fun `sanitizePath collapses double slashes`() {
        assertEquals("docs/file.txt", RcloneCliService.sanitizePath("docs//file.txt"))
    }

    @Test
    fun `sanitizePath removes dot segments`() {
        assertEquals("docs/file.txt", RcloneCliService.sanitizePath("./docs/./file.txt"))
    }

    @Test
    fun `sanitizePath rejects path traversal`() {
        assertFailsWith<IllegalArgumentException> {
            RcloneCliService.sanitizePath("docs/../../../etc/passwd")
        }
    }

    @Test
    fun `sanitizePath returns empty string for root`() {
        assertEquals("", RcloneCliService.sanitizePath("/"))
        assertEquals("", RcloneCliService.sanitizePath(""))
    }

    @Test
    fun `sanitizePath handles backslashes`() {
        assertEquals("docs/file.txt", RcloneCliService.sanitizePath("docs\\file.txt"))
    }

    @Test
    fun `remotePath builds correct path with base and operation path`() {
        val config =
            RcloneConfig(
                remote = "gdrive:",
                path = "subfolder",
                tokenPath =
                    java.nio.file.Paths
                        .get("/tmp/test"),
            )
        val service = RcloneCliService(config)
        assertEquals("gdrive:subfolder/docs/file.txt", service.remotePath("docs/file.txt"))
    }

    @Test
    fun `remotePath with empty base path`() {
        val config =
            RcloneConfig(
                remote = "gdrive:",
                path = "",
                tokenPath =
                    java.nio.file.Paths
                        .get("/tmp/test"),
            )
        val service = RcloneCliService(config)
        assertEquals("gdrive:docs/file.txt", service.remotePath("docs/file.txt"))
    }

    @Test
    fun `remotePath for root listing`() {
        val config =
            RcloneConfig(
                remote = "gdrive:",
                path = "",
                tokenPath =
                    java.nio.file.Paths
                        .get("/tmp/test"),
            )
        val service = RcloneCliService(config)
        assertEquals("gdrive:", service.remotePath(""))
    }

    @Test
    fun `remotePath preserves leading slash for absolute paths`() {
        val config =
            RcloneConfig(
                remote = "sftp-host:",
                path = "/tmp/data",
                tokenPath =
                    java.nio.file.Paths
                        .get("/tmp/test"),
            )
        val service = RcloneCliService(config)
        assertEquals("sftp-host:/tmp/data", service.remotePath(""))
        assertEquals("sftp-host:/tmp/data/docs/file.txt", service.remotePath("docs/file.txt"))
    }
}
