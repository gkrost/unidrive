package org.krost.unidrive.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-235: the "no provider profiles parsed from config" diagnostic flags MSIX-sandbox
 * config-path divergence when the parent process is a packaged-app launcher
 * (Claude Desktop / Store-PowerShell / etc), and surfaces the NTFS FileID so the
 * user can compare the two views of the same path string.
 *
 * The renderer is parameterised over [parentProcessCommand] so the test can simulate
 * a synthetic MSIX parent without forking a child JVM under one.
 */
class ConfigMissingDiagnosticTest {
    // ── looksLikeMsixParent heuristic ────────────────────────────────────────

    @Test
    fun `looksLikeMsixParent matches WindowsApps install path`() {
        assertTrue(
            looksLikeMsixParent(
                "C:\\Program Files\\WindowsApps\\Anthropic.Claude_1.0.0_x64__abc\\claude.exe",
            ),
            "WindowsApps is the canonical MSIX install root",
        )
    }

    @Test
    fun `looksLikeMsixParent matches case-insensitively`() {
        assertTrue(looksLikeMsixParent("c:\\program files\\windowsapps\\foo\\bar.exe"))
    }

    @Test
    fun `looksLikeMsixParent matches Claude launcher path`() {
        assertTrue(looksLikeMsixParent("C:\\Users\\u\\AppData\\Local\\Programs\\Claude\\Claude.exe"))
    }

    @Test
    fun `looksLikeMsixParent rejects native shells`() {
        assertFalse(looksLikeMsixParent("C:\\Windows\\System32\\cmd.exe"))
        assertFalse(looksLikeMsixParent("/usr/bin/bash"))
        assertFalse(looksLikeMsixParent(null))
        assertFalse(looksLikeMsixParent(""))
    }

    // ── full-renderer wiring ─────────────────────────────────────────────────

    @Test
    fun `renderer with MSIX parent emits the sandbox hint block`() {
        val sandbox = Files.createTempDirectory("ud235-")
        try {
            val configFile = sandbox.resolve("config.toml")
            val msg =
                renderConfigMissingMessage(
                    configFile = configFile,
                    parentProcessCommand =
                        "C:\\Program Files\\WindowsApps\\Anthropic.Claude_1.0.0_x64__abc\\claude.exe",
                )
            assertTrue(msg.contains("MSIX-packaged-app child"), "MSIX hint heading expected")
            assertTrue(msg.contains("wcifs virtualization"), "wcifs explanation expected")
            assertTrue(msg.contains("`-c <non-virtualized-path>`"), "workaround #1 (-c flag) expected")
            assertTrue(msg.contains("UNIDRIVE_CONFIG_DIR"), "workaround #2 (env var) expected")
            assertTrue(msg.contains("NTFS FileID"), "FileID line expected for cross-shell comparison")
            assertTrue(
                msg.contains("Parent process: C:\\Program Files\\WindowsApps"),
                "parent-process command must be echoed back for confirmation",
            )
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `renderer with MSIX parent and existing file resolves NTFS FileID`() {
        val sandbox = Files.createTempDirectory("ud235-")
        val configFile = sandbox.resolve("config.toml")
        try {
            Files.writeString(configFile, "[general]\n")
            val msg =
                renderConfigMissingMessage(
                    configFile = configFile,
                    parentProcessCommand = "C:\\Program Files\\WindowsApps\\Foo\\bar.exe",
                )
            // Sanity: when the file exists on a normal FS, fileKey is non-null and the
            // FileID line should NOT degrade to the (unavailable) sentinel.
            val fileIdLine = msg.lineSequence().first { it.contains("NTFS FileID") }
            assertTrue(
                Files.exists(configFile),
                "precondition: temp config file must exist on disk",
            )
            assertFalse(
                fileIdLine.contains("(unavailable"),
                "FileID should resolve when the file exists; got: $fileIdLine",
            )
        } finally {
            Files.deleteIfExists(configFile)
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `renderer without MSIX parent does not emit sandbox hint`() {
        val sandbox = Files.createTempDirectory("ud235-")
        try {
            val configFile = sandbox.resolve("config.toml")
            val msg =
                renderConfigMissingMessage(
                    configFile = configFile,
                    parentProcessCommand = "/usr/bin/bash",
                )
            assertFalse(msg.contains("MSIX-packaged-app"), "no hint without MSIX parent")
            assertFalse(msg.contains("wcifs"), "no wcifs mention without MSIX parent")
            assertFalse(msg.contains("NTFS FileID"), "no FileID line without MSIX parent")
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `renderer with null parent does not emit sandbox hint`() {
        val sandbox = Files.createTempDirectory("ud235-")
        try {
            val configFile = sandbox.resolve("config.toml")
            // Default-arg path: no parent passed at all.
            val msg = renderConfigMissingMessage(configFile)
            assertFalse(msg.contains("MSIX-packaged-app"), "no hint when parent unknown")
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `renderer preserves the unified UD-242 header line`() {
        val sandbox = Files.createTempDirectory("ud235-")
        try {
            val configFile = sandbox.resolve("config.toml")
            val msg = renderConfigMissingMessage(configFile)
            // UD-242 unified wording — locks the first line so a future refactor
            // doesn't silently break shell scripts greping for it.
            assertTrue(
                msg.lineSequence().first().startsWith("Error: no unidrive config found"),
                "first line should be the UD-242 unified wording; got: ${msg.lineSequence().first()}",
            )
            assertTrue(msg.contains("Searched: $configFile"))
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `renderer in verbose mode still surfaces legacy diagnostic fields`() {
        val sandbox = Files.createTempDirectory("ud235-")
        try {
            val configFile = sandbox.resolve("config.toml")
            val msg = renderConfigMissingMessage(configFile, verbose = true)
            assertTrue(msg.contains("APPDATA env:"))
            assertTrue(msg.contains("Files.exists:"))
            assertTrue(msg.contains("Files.size:"))
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }
}
