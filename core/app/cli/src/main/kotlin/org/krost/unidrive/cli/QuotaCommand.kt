package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Command(name = "quota", description = ["Show storage quota"], mixinStandardHelpOptions = true)
class QuotaCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    // #655: --json is the script/face-end read: it NEVER creates a provider, never touches the
    // network and NEVER auto-spawns a daemon (#642's ensureDaemonRunning is MountCommand's and
    // RefreshCommand's behavior, not this command's). It reads the running daemon's
    // daemon.status (the live snapshot, same shape) or, when no daemon runs, the cached
    // tuple from state.db (flagged stale).
    @Option(names = ["--json"], description = [
        "Print the quota snapshot as JSON: the running daemon's live snapshot, else the cached " +
            "one from state.db flagged stale. Read-only: no network, no daemon start.",
    ])
    var json: Boolean = false

    override fun run() {
        if (json) {
            val profile = parent.resolveCurrentProfile()
            val stateDb = parent.configBaseDir().resolve(profile.name).resolve("state.db")
            val code = runJson(profile.name, stateDb)
            if (code != 0) System.exit(code)
            return
        }
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

    // ── #655: the JSON read ──────────────────────────────────────────────────

    /**
     * Read-only snapshot for scripts and front-ends: the running daemon's `quota` object
     * verbatim (same shape as daemon.status), else the cached tuple from state.db flagged
     * stale. Neither exists → exit code 1 with the remedy. No provider, no network, no daemon
     * start — a stopped profile stays stopped.
     */
    internal fun runJson(
        profileName: String,
        stateDb: Path,
    ): Int {
        val socketPath = org.krost.unidrive.sync.IpcServer.defaultSocketPath(profileName)
        if (Files.exists(socketPath)) {
            val reply = queryDaemonStatus(socketPath, profileName)
            if (reply != null) {
                val quota = Json.parseToJsonElement(reply).jsonObject["quota"]
                if (quota != null) {
                    println(quota.toString())
                    return 0
                }
                // A daemon whose provider has no account quota (ProviderMetadata hasQuota
                // = false) serves no quota field at all — that is the answer, not an error.
                println("{\"quota\":null,\"reason\":\"provider has no account quota\"}")
                return 0
            }
            // Socket exists but the daemon did not answer (mid-shutdown): fall through to the cache.
        }
        val cached = readCachedQuota(stateDb)
        if (cached == null) {
            System.err.println(
                "No running daemon and no cached quota for profile '$profileName'. " +
                    "Run 'unidrive -p $profileName quota' once (online) to seed the cache.",
            )
            return 1
        }
        val (quota, fetchedAt) = cached
        println(
            """{"used_bytes":${quota.used},"total_bytes":${quota.total},""" +
                """"fetched_at_ms":${fetchedAt.toEpochMilli()},"stale":true,"error":null}""",
        )
        return 0
    }

    /** One daemon.status round-trip over the authenticated IPC socket; null when it fails. */
    private fun queryDaemonStatus(
        socketPath: java.nio.file.Path,
        profileName: String,
    ): String? =
        try {
            val endpoint = IpcEndpoint(socketPath, parent.providerConfigDir(), profileName)
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
                val buf = java.nio.ByteBuffer.allocate(16 * 1024)
                while (!buf.array().take(buf.position()).contains('\n'.code.toByte())) {
                    if (channel.read(buf) <= 0) break
                }
                buf.flip()
                if (buf.limit() == 0) null else String(buf.array(), 0, buf.limit()).substringBefore('\n')
            }
        } catch (e: Exception) {
            if (e is IpcAuthException) throw e
            null
        }
}
