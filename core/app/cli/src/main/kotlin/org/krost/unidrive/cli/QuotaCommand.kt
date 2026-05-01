package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Command(name = "quota", description = ["Show storage quota"], mixinStandardHelpOptions = true)
class QuotaCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        val provider = parent.createProvider()
        // UD-214: cache last-seen quota in state.db so an offline `unidrive quota`
        // run can still surface a useful number with "as of T" instead of failing
        // silently. Network-failure path reads the cached tuple; success path
        // overwrites it. Persisting via sync_state (key/value pairs) — no schema
        // change.
        val profile = parent.resolveCurrentProfile()
        val stateDb = parent.configBaseDir().resolve(profile.name).resolve("state.db")
        try {
            runBlocking {
                provider.authenticateAndLog()
                val quota = provider.quota()
                cacheQuota(stateDb, quota)
                println("Storage Quota (${provider.displayName}):")
                println("  Used:      ${CliProgressReporter.formatSize(quota.used)}")
                println("  Total:     ${CliProgressReporter.formatSize(quota.total)}")
                println("  Remaining: ${CliProgressReporter.formatSize(quota.remaining)}")
            }
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, provider)
        } catch (e: Exception) {
            // UD-214: on network / provider failure, try the cached snapshot.
            val cached = readCachedQuota(stateDb)
            if (cached != null) {
                val (quota, fetchedAt) = cached
                val ago = formatRelative(fetchedAt)
                System.err.println(
                    "Warning: live quota unavailable (${e.javaClass.simpleName}: ${e.message}); " +
                        "showing cached value as of $ago.",
                )
                println("Storage Quota (${provider.displayName}, cached):")
                println("  Used:      ${CliProgressReporter.formatSize(quota.used)}")
                println("  Total:     ${CliProgressReporter.formatSize(quota.total)}")
                println("  Remaining: ${CliProgressReporter.formatSize(quota.remaining)}")
                println("  As of:     ${fetchedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
                return
            }
            throw e
        }
    }

    private fun cacheQuota(
        stateDb: java.nio.file.Path,
        quota: QuotaInfo,
    ) {
        if (!Files.exists(stateDb)) return // no state DB yet — nothing to update
        runCatching {
            val db = StateDatabase(stateDb)
            try {
                db.initialize()
                db.setSyncState("quota_used", quota.used.toString())
                db.setSyncState("quota_total", quota.total.toString())
                db.setSyncState("quota_remaining", quota.remaining.toString())
                db.setSyncState("quota_fetched_at", Instant.now().toString())
            } finally {
                db.close()
            }
        } // best-effort: silent on failure — the live display already showed the user the real numbers.
    }

    private fun readCachedQuota(stateDb: java.nio.file.Path): Pair<QuotaInfo, Instant>? {
        if (!Files.exists(stateDb)) return null
        return runCatching {
            val db = StateDatabase(stateDb)
            try {
                db.initialize()
                val used = db.getSyncState("quota_used")?.toLongOrNull() ?: return@runCatching null
                val total = db.getSyncState("quota_total")?.toLongOrNull() ?: return@runCatching null
                val remaining = db.getSyncState("quota_remaining")?.toLongOrNull() ?: return@runCatching null
                val fetchedAtStr = db.getSyncState("quota_fetched_at") ?: return@runCatching null
                val fetchedAt = Instant.parse(fetchedAtStr)
                QuotaInfo(used = used, total = total, remaining = remaining) to fetchedAt
            } finally {
                db.close()
            }
        }.getOrNull()
    }

    private fun formatRelative(t: Instant): String {
        val ageMs = System.currentTimeMillis() - t.toEpochMilli()
        return when {
            ageMs < 60_000 -> "${ageMs / 1000}s ago"
            ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
            ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ago"
            else -> "${ageMs / 86_400_000}d ago"
        }
    }
}
