package org.krost.unidrive.cli

import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatusCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `status command is registered`() {
        val statusCmd = cmd.subcommands["status"]
        assertNotNull(statusCmd, "status subcommand should be registered")
    }

    @Test
    fun `status command has --all flag`() {
        val statusCmd = cmd.subcommands["status"]!!
        val options = statusCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--all" in options)
    }

    @Test
    fun `status command --all has -a short alias`() {
        val statusCmd = cmd.subcommands["status"]!!
        val allNames = statusCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-a" in allNames)
    }

    // UD-756 — HYDRATED / PENDING bucket split

    private fun fileEntry(
        path: String,
        size: Long,
        remoteId: String?,
        isHydrated: Boolean,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = remoteId?.let { "h" },
        remoteSize = if (remoteId != null) size else 0L,
        remoteModified = remoteId?.let { Instant.now() },
        localMtime = if (isHydrated) 1711627200000 else null,
        localSize = if (isHydrated) size else null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = if (remoteId != null) Instant.now() else Instant.EPOCH,
    )

    @Test
    fun `UD-756 mixed entries split into hydrated and pending buckets`() {
        // Three hydrated-and-uploaded files (HYDRATED bucket)
        // Two pending uploads (PENDING bucket)
        // One sparse placeholder (counted nowhere — bytes aren't on disk)
        // One folder (skipped)
        val entries =
            listOf(
                fileEntry("/a.bin", size = 1_000, remoteId = "r1", isHydrated = true),
                fileEntry("/b.bin", size = 2_000, remoteId = "r2", isHydrated = true),
                fileEntry("/c.bin", size = 3_000, remoteId = "r3", isHydrated = true),
                fileEntry("/pending1.bin", size = 5_000, remoteId = null, isHydrated = true),
                fileEntry("/pending2.bin", size = 7_000, remoteId = null, isHydrated = true),
                fileEntry("/sparse.bin", size = 9_000, remoteId = "r4", isHydrated = false),
                fileEntry("/folder", size = 0, remoteId = "rf", isHydrated = true, isFolder = true),
            )

        val buckets = computeLocalSizeBuckets(entries)

        assertEquals(6_000L, buckets.hydratedBytes, "hydrated = 1000 + 2000 + 3000")
        assertEquals(12_000L, buckets.pendingBytes, "pending = 5000 + 7000")
    }

    @Test
    fun `UD-756 only-pending profile reports zero hydrated`() {
        val entries =
            listOf(
                fileEntry("/p1.bin", size = 100, remoteId = null, isHydrated = true),
                fileEntry("/p2.bin", size = 200, remoteId = null, isHydrated = true),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(0L, buckets.hydratedBytes)
        assertEquals(300L, buckets.pendingBytes)
    }

    @Test
    fun `UD-756 only-hydrated profile reports zero pending`() {
        val entries =
            listOf(
                fileEntry("/h1.bin", size = 1_000, remoteId = "r1", isHydrated = true),
                fileEntry("/h2.bin", size = 2_000, remoteId = "r2", isHydrated = true),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(3_000L, buckets.hydratedBytes)
        assertEquals(0L, buckets.pendingBytes)
    }

    @Test
    fun `UD-756 sparse placeholder contributes nothing`() {
        // Sparse placeholders have isHydrated=false and localSize=null. They
        // belong in the SPARSE column, not in HYDRATED or PENDING.
        val entries =
            listOf(
                fileEntry("/s.bin", size = 10_000, remoteId = "r", isHydrated = false),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(0L, buckets.hydratedBytes)
        assertEquals(0L, buckets.pendingBytes)
    }
}
