package org.krost.unidrive.cli

import org.krost.unidrive.sync.RelocateMdc
import org.slf4j.MDC
import picocli.CommandLine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelocateCommandTest {
    private val cmd = CommandLine(Main())
    private val relocateCmd get() = cmd.subcommands["relocate"]!!

    // -- Command registration ---------------------------------------------------

    @Test
    fun `relocate command is registered`() {
        assertNotNull(cmd.subcommands["relocate"], "relocate subcommand should be registered")
    }

    // -- Option flags -----------------------------------------------------------

    @Test
    fun `--from option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--from" in it.names() }
        assertTrue(opt.required(), "--from must be required")
    }

    @Test
    fun `--from has -f short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-f" in allNames)
    }

    @Test
    fun `--to option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--to" in it.names() }
        assertTrue(opt.required(), "--to must be required")
    }

    @Test
    fun `--to has -t short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-t" in allNames)
    }

    @Test
    fun `--delete-source flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--delete-source" in options)
    }

    @Test
    fun `--delete-source defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--delete-source" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    @Test
    fun `--source-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--source-path" in options)
    }

    @Test
    fun `--target-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--target-path" in options)
    }

    @Test
    fun `--buffer-mb option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--buffer-mb" in options)
    }

    @Test
    fun `--force flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--force" in options)
    }

    @Test
    fun `--force defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--force" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    // -- formatSize helper ------------------------------------------------------

    @Test
    fun `formatSize formats bytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("0 B", method.invoke(cmd, 0L))
        assertEquals("512 B", method.invoke(cmd, 512L))
    }

    @Test
    fun `formatSize formats kibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 KiB", method.invoke(cmd, 1024L))
        assertEquals("1.5 KiB", method.invoke(cmd, 1536L))
    }

    @Test
    fun `formatSize formats mebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 MiB", method.invoke(cmd, 1024L * 1024))
    }

    @Test
    fun `formatSize formats gibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 GiB", method.invoke(cmd, 1024L * 1024 * 1024))
    }

    @Test
    fun `formatSize formats tebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 TiB", method.invoke(cmd, 1024L * 1024 * 1024 * 1024))
    }

    // -- UD-294: relocate seeds profile + scan MDC ------------------------------
    //
    // Pre-fix: relocate never put `profile` or `scan` into MDC, so the
    // runBlocking(MDCContext()) snapshots captured an empty value and every
    // log line rendered [<sha>] [*] [-------] (132 k unfilterable lines on
    // the 2026-04-29 baseline). UD-284 fixed MDCContext propagation; this
    // ticket fills the snapshot that propagates.

    @BeforeTest
    fun clearRelocateMdc() {
        MDC.remove("profile")
        MDC.remove("scan")
    }

    @AfterTest
    fun clearRelocateMdcAfter() {
        MDC.remove("profile")
        MDC.remove("scan")
    }

    @Test
    fun `UD-294 - newMigId returns mig-prefix and 8 hex chars`() {
        val migId = RelocateMdc.newMigId()
        assertTrue(
            migId.matches(Regex("mig-[0-9a-f]{8}")),
            "expected `mig-<8 hex chars>`; was '$migId'",
        )
    }

    @Test
    fun `UD-294 - newMigId is unique across calls`() {
        // 8 hex chars = 32 bits = collision probability over 100 calls is
        // ~5.7e-7. We assert disjoint here as a smoke test that the UUID
        // randomness is wired up.
        val ids = (1..100).map { RelocateMdc.newMigId() }.toSet()
        assertEquals(100, ids.size, "expected all 100 mig ids unique")
    }

    @Test
    fun `UD-294 - withRelocateMdc seeds profile=from+to and scan=migId during block`() {
        var capturedProfile: String? = null
        var capturedScan: String? = null
        RelocateMdc.withRelocateMdc("src-fs", "dst-webdav", "mig-deadbeef") {
            capturedProfile = MDC.get("profile")
            capturedScan = MDC.get("scan")
        }
        assertEquals("src-fs+dst-webdav", capturedProfile)
        assertEquals("mig-deadbeef", capturedScan)
    }

    @Test
    fun `UD-294 - withRelocateMdc clears MDC after block when no prior values`() {
        RelocateMdc.withRelocateMdc("a", "b", "mig-00000000") { }
        assertNull(MDC.get("profile"), "profile must be cleared post-block")
        assertNull(MDC.get("scan"), "scan must be cleared post-block")
    }

    @Test
    fun `UD-294 - withRelocateMdc restores prior MDC values on normal exit`() {
        MDC.put("profile", "outer-profile")
        MDC.put("scan", "outer-scan")
        try {
            RelocateMdc.withRelocateMdc("a", "b", "mig-11111111") { }
            assertEquals("outer-profile", MDC.get("profile"), "outer profile must be restored")
            assertEquals("outer-scan", MDC.get("scan"), "outer scan must be restored")
        } finally {
            MDC.remove("profile")
            MDC.remove("scan")
        }
    }

    @Test
    fun `UD-294 - withRelocateMdc restores prior MDC values when block throws`() {
        MDC.put("profile", "outer-profile")
        MDC.put("scan", "outer-scan")
        try {
            try {
                RelocateMdc.withRelocateMdc("a", "b", "mig-22222222") {
                    throw RuntimeException("boom")
                }
            } catch (e: RuntimeException) {
                // expected
            }
            assertEquals("outer-profile", MDC.get("profile"), "outer profile must be restored on throw")
            assertEquals("outer-scan", MDC.get("scan"), "outer scan must be restored on throw")
        } finally {
            MDC.remove("profile")
            MDC.remove("scan")
        }
    }
}
