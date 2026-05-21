package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-317: internxt's listing API occasionally returns names with a leading `\n` (e.g.
 * `"\ninternxt-cli.desktop"`). Those flow through to `SyncEngine`, which then fails URI
 * construction with `Illegal char <\n> at index 0` and marks the action failed.
 *
 * Covers [InternxtProvider.sanitizeName] and the model-to-CloudItem conversions end-to-end so a
 * regression anywhere in the pipeline (toCloudItem, toDeltaCloudItem) is caught.
 */
class InternxtNameSanitizationTest {
    // -- pure sanitizeName --------------------------------------------------------------------

    @Test
    fun `leading newline is stripped`() {
        assertEquals("internxt-cli.desktop", InternxtProvider.sanitizeName("\ninternxt-cli.desktop"))
    }

    @Test
    fun `surrounding spaces are stripped`() {
        assertEquals("spaced.txt", InternxtProvider.sanitizeName("   spaced.txt   "))
    }

    @Test
    fun `trailing carriage return is stripped`() {
        assertEquals("name.txt", InternxtProvider.sanitizeName("name.txt\r\n"))
    }

    @Test
    fun `tab-wrapped name is stripped`() {
        assertEquals("report", InternxtProvider.sanitizeName("\treport\t"))
    }

    @Test
    fun `clean name is unchanged`() {
        assertEquals("clean.pdf", InternxtProvider.sanitizeName("clean.pdf"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", InternxtProvider.sanitizeName(""))
    }

    @Test
    fun `interior whitespace is preserved`() {
        // Only leading/trailing whitespace is stripped — internal spaces are legal filename chars.
        assertEquals("my file name", InternxtProvider.sanitizeName("  my file name  "))
    }

    // -- file conversion (covers listChildren + getMetadata code paths) -----------------------

    @Test
    fun `fileToCloudItem trims leading newline from plainName`() {
        val file =
            InternxtFile(
                uuid = "file-1",
                plainName = "\ninternxt-cli.desktop",
                type = null,
                size = "1234",
            )
        val item = InternxtProvider.fileToCloudItem(file, parentPath = "/")
        assertEquals("internxt-cli.desktop", item.name)
        assertEquals("/internxt-cli.desktop", item.path)
        assertFalse(item.name.startsWith("\n"), "name must not retain leading newline — breaks URI construction")
        assertFalse(item.path.contains("\n"), "path must not contain control chars")
    }

    @Test
    fun `fileToCloudItem trims spaces around plainName and appends type`() {
        val file =
            InternxtFile(
                uuid = "file-2",
                plainName = "   spaced   ",
                type = "txt",
                size = "0",
            )
        val item = InternxtProvider.fileToCloudItem(file, parentPath = "/Docs")
        assertEquals("spaced.txt", item.name)
        assertEquals("/Docs/spaced.txt", item.path)
    }

    @Test
    fun `fileToCloudItem trims type when it arrives with whitespace`() {
        val file =
            InternxtFile(
                uuid = "file-3",
                plainName = "report",
                type = "\npdf",
                size = "0",
            )
        val item = InternxtProvider.fileToCloudItem(file, parentPath = "/")
        assertEquals("report.pdf", item.name)
    }

    @Test
    fun `fileToCloudItem falls back to name when plainName is null`() {
        val file =
            InternxtFile(
                uuid = "file-4",
                name = " encrypted-blob ",
                plainName = null,
                type = "bin",
                size = "0",
            )
        val item = InternxtProvider.fileToCloudItem(file, parentPath = "/")
        assertEquals("encrypted-blob.bin", item.name)
    }

    // -- folder conversion --------------------------------------------------------------------

    @Test
    fun `folderToCloudItem trims leading newline`() {
        val folder =
            InternxtFolder(
                uuid = "folder-1",
                plainName = "\nDocuments",
            )
        val item = InternxtProvider.folderToCloudItem(folder, parentPath = "/")
        assertEquals("Documents", item.name)
        assertEquals("/Documents", item.path)
        assertTrue(item.isFolder)
    }

    @Test
    fun `folderToCloudItem trims surrounding whitespace`() {
        val folder =
            InternxtFolder(
                uuid = "folder-2",
                plainName = "  Work  ",
            )
        val item = InternxtProvider.folderToCloudItem(folder, parentPath = "/root")
        assertEquals("Work", item.name)
        assertEquals("/root/Work", item.path)
    }

    // -- delta pathway (covers delta() code path) ---------------------------------------------

    @Test
    fun `fileToDeltaCloudItem trims name and uses provided parent path`() {
        val file =
            InternxtFile(
                uuid = "file-5",
                plainName = "\ninternxt-cli.desktop",
                type = null,
                size = "0",
            )
        val item = InternxtProvider.fileToDeltaCloudItem(file, parentPath = "/downloads")
        assertEquals("internxt-cli.desktop", item.name)
        assertEquals("/downloads/internxt-cli.desktop", item.path)
    }

    @Test
    fun `folderToDeltaCloudItem trims name`() {
        val folder = InternxtFolder(uuid = "folder-3", plainName = "   Projects   ")
        val item = InternxtProvider.folderToDeltaCloudItem(folder, parentPath = "")
        assertEquals("Projects", item.name)
        assertEquals("/Projects", item.path)
    }

    // -- end-to-end regression: response stub --------------------------------------------------

    /**
     * Simulates a `FolderContentResponse` coming off the wire with the exact pathology observed in
     * the 2026-04-19 JFR capture: one file name with a leading `\n`, one with surrounding spaces.
     * Verifies the CloudItems emitted to SyncEngine are all clean — no leading/trailing whitespace,
     * no control chars at the start of the path.
     */
    @Test
    fun `listFolder-equivalent pipeline scrubs unsanitary names`() {
        val response =
            FolderContentResponse(
                children =
                    listOf(
                        InternxtFolder(uuid = "d1", plainName = "\nMessy Folder"),
                        InternxtFolder(uuid = "d2", plainName = "   Spaced Dir   "),
                        InternxtFolder(uuid = "d3", plainName = "Clean"),
                    ),
                files =
                    listOf(
                        InternxtFile(uuid = "f1", plainName = "\ninternxt-cli.desktop", type = null, size = "42"),
                        InternxtFile(uuid = "f2", plainName = "   spaced.txt   ", type = null, size = "1"),
                        InternxtFile(uuid = "f3", plainName = "normal", type = "pdf", size = "0"),
                    ),
            )

        // Mirror what InternxtProvider.listChildren does:
        //   content.children.map { it.toCloudItem(path) } + content.files.map { it.toCloudItem(path) }
        val items =
            response.children.map { InternxtProvider.folderToCloudItem(it, "/") } +
                response.files.map { InternxtProvider.fileToCloudItem(it, "/") }

        // Every name and path must be free of leading/trailing whitespace.
        for (item in items) {
            assertEquals(item.name.trim(), item.name, "name not trimmed for ${item.id}: ${repr(item.name)}")
            assertFalse(item.path.contains('\n'), "path contains newline for ${item.id}: ${repr(item.path)}")
            assertFalse(item.path.contains('\r'), "path contains CR for ${item.id}: ${repr(item.path)}")
            assertFalse(
                item.path.removePrefix("/").startsWith(" "),
                "path starts with whitespace for ${item.id}: ${repr(item.path)}",
            )
        }

        // Specific regression assertions for the log-observed inputs.
        val desktop = items.single { it.id == "f1" }
        assertEquals("internxt-cli.desktop", desktop.name)
        assertEquals("/internxt-cli.desktop", desktop.path)

        val spacedFile = items.single { it.id == "f2" }
        assertEquals("spaced.txt", spacedFile.name)

        val messyFolder = items.single { it.id == "d1" }
        assertEquals("Messy Folder", messyFolder.name)
    }

    private fun repr(s: String): String =
        s
            .map {
                when {
                    it == '\n' -> "\\n"
                    it == '\r' -> "\\r"
                    it == '\t' -> "\\t"
                    it.code < 0x20 -> "\\x%02x".format(it.code)
                    else -> it.toString()
                }
            }.joinToString("")
}
