package org.krost.unidrive.sync

import org.slf4j.MDC
import java.util.UUID

/**
 * UD-294: relocate-side MDC seeding.
 *
 * Sync flows have `Main.resolveCurrentProfile` (CLI) or `ProfileContext` (MCP)
 * to seed the `profile` MDC key — relocate doesn't fit either, since it touches
 * TWO profiles (`--from` + `--to`). Without an explicit seed the
 * `runBlocking(MDCContext()) { ... }` snapshot in CLI/MCP relocate handlers
 * captures an empty `profile`/`scan` and every downstream log line renders
 * `[<sha>] [*] [-------]` (132 k unfilterable lines on the 2026-04-29 baseline,
 * see `docs/dev/log-feedback-loop-proposal.md`).
 *
 * Used by:
 *  - `org.krost.unidrive.cli.RelocateCommand.run()`
 *  - `org.krost.unidrive.mcp.RelocateTool.handleRelocate()`
 *
 * Mirrors the put-with-restore pattern of [SyncEngine]'s `runScan`
 * (sync.SyncEngine.kt:80) so nested relocates inside a sync don't clobber
 * the outer scan/profile MDC permanently.
 */
object RelocateMdc {
    /**
     * Generate a short, unique migration ID. Format: `mig-<8 hex chars>`.
     * 32 bits → collision probability ~1 in 100 M; sufficient for tagging
     * a single process's worth of migrations in `unidrive.log`.
     */
    fun newMigId(): String = "mig-" + UUID.randomUUID().toString().substring(0, 8)

    /**
     * Run [block] with `profile` MDC seeded as `<from>+<to>` and `scan` as [migId].
     * Restores prior MDC values (or removes the keys if they weren't set) on
     * normal completion AND on exception.
     */
    inline fun <T> withRelocateMdc(
        from: String,
        to: String,
        migId: String,
        block: () -> T,
    ): T {
        val priorProfile = MDC.get("profile")
        val priorScan = MDC.get("scan")
        MDC.put("profile", "$from+$to")
        MDC.put("scan", migId)
        try {
            return block()
        } finally {
            if (priorProfile == null) MDC.remove("profile") else MDC.put("profile", priorProfile)
            if (priorScan == null) MDC.remove("scan") else MDC.put("scan", priorScan)
        }
    }
}
