package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for UD-246 — `provider info` accepts case/whitespace variants,
 * and the `--help` text advertises the full runtime provider set.
 */
class ProviderInfoCommandTest {
    // ── ProviderRegistry.resolveId (pure) ────────────────────────────────────

    @Test
    fun `resolveId accepts canonical lowercase`() {
        assertEquals("onedrive", ProviderRegistry.resolveId("onedrive"))
    }

    @Test
    fun `resolveId case and whitespace variants all resolve`() {
        // Table: raw input → expected canonical id.
        val cases =
            listOf(
                "onedrive" to "onedrive",
                "ONEDRIVE" to "onedrive",
                "OneDrive" to "onedrive",
                "  onedrive" to "onedrive",
                "onedrive  " to "onedrive",
                "  onedrive  " to "onedrive",
                "\tonedrive\n" to "onedrive",
                "S3" to "s3",
                "SFTP" to "sftp",
                "WebDAV" to "webdav",
                "HiDrive" to "hidrive",
                "Internxt" to "internxt",
                "LocalFS" to "localfs",
                "RClone" to "rclone",
            )
        for ((raw, expected) in cases) {
            assertEquals(expected, ProviderRegistry.resolveId(raw), "raw=<$raw>")
        }
    }

    @Test
    fun `resolveId rejects unknown and empty inputs`() {
        val unknown =
            listOf(
                "nosuch",
                "/../../etc/passwd",
                "",
                "   ",
                "\t\n",
                "google-drive",
                "dropbox",
            )
        for (raw in unknown) {
            assertNull(ProviderRegistry.resolveId(raw), "raw=<$raw> should be unknown")
        }
    }

    // ── knownTypes / help text ───────────────────────────────────────────────

    @Test
    fun `knownTypes contains every runtime ServiceLoader provider`() {
        // This list must match the set of providers that ProviderFactory
        // actually constructs (see META-INF/services under core/providers/*).
        val expected = setOf("hidrive", "internxt", "localfs", "onedrive", "rclone", "s3", "sftp", "webdav")
        assertEquals(expected, ProviderRegistry.knownTypes)
    }

    @Test
    fun `provider info help lists every runtime provider id`() {
        val cmd = CommandLine(Main())
        val infoCmd = cmd.subcommands["provider"]!!.subcommands["info"]!!
        val helpText = infoCmd.usageMessage

        // Canonical runtime set — all eight must appear somewhere in the help
        // output so the CLI's self-documentation doesn't lie to the user.
        for (id in ProviderRegistry.knownTypes) {
            assertTrue(
                helpText.contains(id),
                "provider info --help text missing '$id'. Full help:\n$helpText",
            )
        }
    }

    // ── resolveId + getMetadata end-to-end ───────────────────────────────────

    @Test
    fun `case-insensitive lookup returns metadata for every runtime provider`() {
        for (id in ProviderRegistry.knownTypes) {
            val upper = id.uppercase()
            val canonical = ProviderRegistry.resolveId("  $upper  ")
            assertEquals(id, canonical, "round-trip failed for $id")
            assertNotNull(
                ProviderRegistry.getMetadata(canonical!!),
                "no metadata for canonical id=$canonical",
            )
        }
    }
}
