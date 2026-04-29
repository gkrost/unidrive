package org.krost.unidrive.cli

import org.krost.unidrive.QuotaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusAuditTest {
    // Byte sizes picked to match the 2026-04-19 MCP probe numbers quoted in the
    // UD-316 ticket. UD-238 relabelled the formatter from GB to GiB (the math was
    // always binary); numbers are unchanged, only the suffix flips.
    private val GiB = 1024L * 1024 * 1024

    private fun gib(x: Double): Long = (x * GiB).toLong()

    @Test
    fun `formatBytesBinary emits one-decimal GiB for multi-GiB values`() {
        assertEquals("372.5 GiB", StatusAudit.formatBytesBinary(gib(372.5)))
        assertEquals("1104.9 GiB", StatusAudit.formatBytesBinary(gib(1104.9)))
    }

    @Test
    fun `formatAuditReport contains ticket-shape numbers and delta hint`() {
        val report =
            AuditReport(
                profileName = "onedrive-test",
                providerDisplayName = "OneDrive",
                quota = QuotaInfo(total = gib(1104.9), used = gib(372.5), remaining = gib(1104.9) - gib(372.5)),
                trackedFiles = 57874,
                trackedBytes = gib(109.0),
                nonHydrated = 1224,
                pendingCursor = "cursor-token-xyz",
                lastFullScan = null,
                includeShared = false,
            )

        val output = StatusAudit.formatAuditReport(report)

        assertTrue(output.contains("57,874 entries"), "output contains tracked count: $output")
        assertTrue(output.contains("372.5 GiB used"), "output contains remote quota used: $output")
        assertTrue(output.contains("1104.9 GiB total"), "output contains remote quota total: $output")
        assertTrue(output.contains("109.0 GiB"), "output contains tracked bytes: $output")
        assertTrue(output.contains("263.5 GiB of remote not yet in state.db"), "output contains delta bytes (~263.5 GiB): $output")
        assertTrue(output.contains("run `unidrive -p onedrive-test sync`"), "output shows sync hint: $output")
        assertTrue(output.contains("1,224 entries waiting"), "output shows non-hydrated count: $output")
        assertTrue(output.contains("Pending cursor:   present"), "output shows pending cursor: $output")
        assertTrue(output.contains("Include shared:   false"), "output shows include_shared flag: $output")
    }

    @Test
    fun `severityFor returns RED when delta exceeds 25 percent`() {
        val report =
            AuditReport(
                profileName = "onedrive-test",
                providerDisplayName = "OneDrive",
                quota = QuotaInfo(total = gib(1104.9), used = gib(372.5), remaining = gib(732.4)),
                trackedFiles = 57874,
                trackedBytes = gib(109.0),
                nonHydrated = 1224,
                pendingCursor = "cursor",
                lastFullScan = null,
                includeShared = false,
            )
        assertEquals(AuditSeverity.RED, StatusAudit.severityFor(report))
    }

    @Test
    fun `severityFor returns GREEN when delta below 5 percent`() {
        val report =
            AuditReport(
                profileName = "onedrive-test",
                providerDisplayName = "OneDrive",
                quota = QuotaInfo(total = gib(1000.0), used = gib(100.0), remaining = gib(900.0)),
                trackedFiles = 10000,
                trackedBytes = gib(99.0),
                nonHydrated = 0,
                pendingCursor = null,
                lastFullScan = null,
                includeShared = null,
            )
        assertEquals(AuditSeverity.GREEN, StatusAudit.severityFor(report))
    }

    @Test
    fun `severityFor returns YELLOW for 5-25 percent delta`() {
        val report =
            AuditReport(
                profileName = "p",
                providerDisplayName = "OneDrive",
                quota = QuotaInfo(total = gib(1000.0), used = gib(200.0), remaining = gib(800.0)),
                trackedFiles = 100,
                trackedBytes = gib(170.0),
                nonHydrated = 0,
                pendingCursor = null,
                lastFullScan = null,
                includeShared = null,
            )
        assertEquals(AuditSeverity.YELLOW, StatusAudit.severityFor(report))
    }

    @Test
    fun `formatAuditReport degrades gracefully when provider has no quota`() {
        val report =
            AuditReport(
                profileName = "sftp-home",
                providerDisplayName = "SFTP",
                quota = null,
                trackedFiles = 42,
                trackedBytes = gib(1.5),
                nonHydrated = 0,
                pendingCursor = null,
                lastFullScan = null,
                includeShared = null,
            )
        val output = StatusAudit.formatAuditReport(report)
        assertTrue(output.contains("Remote quota:     unavailable"), "unavailable line present: $output")
        assertTrue(!output.contains("Delta:"), "delta line absent when no quota: $output")
        assertTrue(!output.contains("Include shared"), "include_shared hidden for non-OneDrive: $output")
    }

    @Test
    fun `status command has --audit flag registered via picocli`() {
        val cmd = picocli.CommandLine(Main())
        val statusCmd = cmd.subcommands["status"]!!
        val options = statusCmd.commandSpec.options().map { it.longestName() }
        assertTrue(options.contains("--audit"), "--audit flag should be registered")
    }
}
