package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.cli.LegacyInventory.PathClass
import org.krost.unidrive.cli.StatusAudit.formatBytesBinary
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.SyncScope
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Path
import java.nio.file.Paths

/**
 * #560: the one-time conversion of a legacy profile (one created before the cutover, in the coordinated
 * mirror + mount state) to its fixed mode. See docs/adr/independent-profiles.md, "Migration". Only the first,
 * read-only step exists so far: [MigrateInventoryCommand].
 */
@Command(
    name = "migrate",
    description = ["One-time conversion of a legacy profile: inventory (read-only), convert, upload-quarantined"],
    mixinStandardHelpOptions = true,
    subcommands = [MigrateInventoryCommand::class, MigrateConvertCommand::class, MigrateUploadQuarantinedCommand::class],
)
class MigrateCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive -p <profile> migrate <inventory|convert|upload-quarantined>")
    }
}

/**
 * #560 U5a: `unidrive -p <profile> migrate inventory`. Reads a stopped profile and reports what a conversion would
 * have to preserve; changes nothing (see [LegacyInventory]). Exit code 0 with a report, 1 when refused (the profile
 * is in use, or its state.db cannot be read consistently).
 */
@Command(
    name = "inventory",
    description = [
        "Read-only inventory of a stopped legacy profile before its conversion: config, state.db, the bytes in",
        "the sync_root and the hydration cache, upload staging and client recovery files. Reads every local file",
        "once to compare content hashes. Changes nothing; no network.",
    ],
    mixinStandardHelpOptions = true,
)
class MigrateInventoryCommand : Runnable {
    @ParentCommand
    lateinit var migrate: MigrateCommand

    @Option(names = ["--json"], description = ["Emit the report as JSON"])
    var json: Boolean = false

    @Option(
        names = ["--client-state-dir"],
        description = [
            "Directory holding the platform client's recovery files (<profile>.uploads, .writes, .journal).",
            "Default on Windows: %LOCALAPPDATA%/unidrive/state. Listed by name and size only.",
        ],
    )
    var clientStateDir: String? = null

    override fun run() {
        val main = migrate.parent
        val profile = main.resolveCurrentProfile()
        val raw = profile.rawProvider
        val config = runCatching { main.loadSyncConfig() }
        // Like doctor: a wedged config must not stop a read-only report; fall back to what the raw section says.
        val excludes =
            config.map { it.effectiveExcludePatterns(profile.name) }
                .getOrElse { SyncConfig.DEFAULT_EXCLUDE_PATTERNS + raw?.exclude_patterns.orEmpty() }
        val syncPaths = config.map { it.syncPaths }.getOrElse { runCatching { SyncScope.normalize(raw?.sync_path.orEmpty()) }.getOrDefault(emptyList()) }
        val facts =
            buildList {
                // Allow-list: never a credential key.
                raw?.sync_root?.let { add("sync_root (config)" to it) }
                raw?.root_path?.let { add("root_path" to it) }
                raw?.hydration_cache_max_bytes?.let { add("hydration_cache_max_bytes" to it.toString()) }
                raw?.sync_root_rescan_minutes?.let { add("sync_root_rescan_minutes" to it.toString()) }
                raw?.fast_bootstrap?.let { add("fast_bootstrap" to it.toString()) }
                raw?.keep_overwritten?.let { add("keep_overwritten" to it.toString()) }
                if (profile.isOrphan) add("orphan" to "true (no [providers.${profile.name}] section)")
                config.exceptionOrNull()?.let { add("config error" to (it.message ?: it.javaClass.simpleName)) }
            }
        val inputs =
            LegacyInventory.Inputs(
                profileName = profile.name,
                providerType = profile.type,
                profileDir = main.providerConfigDir(),
                syncRoot = profile.syncRoot,
                cacheDir = SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profile.name),
                syncPaths = syncPaths,
                excludePatterns = excludes,
                configFacts = facts,
                clientStateDir = clientStateDir?.let { Paths.get(it) } ?: defaultClientStateDir(),
            )
        when (val outcome = LegacyInventory.collect(inputs)) {
            is LegacyInventory.Outcome.Refused -> {
                System.err.println(outcome.message)
                System.exit(1)
            }
            is LegacyInventory.Outcome.Collected ->
                println(if (json) renderJson(outcome.report) else renderText(outcome.report, detail = main.verbose))
        }
    }

    companion object {
        private const val TEXT_PATH_LIMIT = 50

        // The Windows client keeps its recovery files under %LOCALAPPDATA%\unidrive\state; other platforms have none.
        internal fun defaultClientStateDir(): Path? {
            if (!System.getProperty("os.name").lowercase().contains("win")) return null
            return System.getenv("LOCALAPPDATA")?.let { Paths.get(it, "unidrive", "state") }
        }

        private fun paths(
            label: String,
            list: List<String>,
            detail: Boolean,
        ): List<String> {
            val out = mutableListOf("  $label: ${list.size}")
            if (detail) list.forEach { out += "      $it" }
            return out
        }

        internal fun renderText(
            report: LegacyInventory.Report,
            detail: Boolean,
        ): String {
            val i = report.inputs
            val lines = mutableListOf<String>()
            lines += "Inventory of legacy profile '${i.profileName}' (read-only)"
            lines += ""
            lines += "1. Profile"
            lines += "  provider:          ${i.providerType}"
            lines += "  profile dir:       ${i.profileDir}"
            lines += "  sync_root:         ${i.syncRoot}"
            lines += "  sync_path:         ${if (i.syncPaths.isEmpty()) "(whole drive)" else i.syncPaths.joinToString(", ")}"
            lines += "  hydration cache:   ${i.cacheDir}"
            i.configFacts.forEach { (k, v) -> lines += "  $k: $v" }
            lines += "  exclude patterns:  ${i.excludePatterns.size}"
            if (detail) i.excludePatterns.forEach { lines += "      $it" }
            lines +=
                "  content hash:      " +
                (report.contentHash ?: "none (remote_hash is not a content hash for this provider; copies compare with local_hash only)")
            lines +=
                "  process lock:      " +
                when {
                    !report.lock.lockFilePresent -> "no lock file"
                    report.lock.recordedHolderPid != null -> "free (stale .lock.pid names PID ${report.lock.recordedHolderPid})"
                    else -> "free"
                }
            lines += ""
            lines += "2. State (consistent snapshot of state.db)"
            val db = report.db
            if (db == null) {
                lines += "  no state.db: every local file is untracked"
            } else {
                lines += "  schema version:    ${db.schemaVersion ?: "none"}"
                lines += "  rows by status:    ${db.rowsByStatus.entries.joinToString(", ") { "${it.key} ${it.value}" }}"
                lines += "  alive:             ${db.aliveFiles} files, ${db.aliveFolders} folders"
                lines += "  local: ids:        ${db.localIdRows}"
                lines += paths("pending uploads (state.db predicate)", db.pendingUploads, detail)
                lines += paths("  replayable by the mount (cache copy, in scope, not excluded)", db.pendingMountReplayable, detail)
                lines += paths("  uploadable by the mirror (sync_root copy)", db.pendingMirror, detail)
                lines += paths("  with no local copy", db.pendingWithoutBytes, detail)
                lines += paths("local: rows not hydrated", db.localUnhydrated, detail)
                lines += paths("upload refused", db.uploadRefused, detail)
                lines += paths("last_error_at set", db.lastErrorAt, detail)
                lines += "  download quarantined: ${db.downloadQuarantined}"
                lines += "  cache_backed (hydrated files): ${listOf("true", "false", "null").joinToString(", ") { "$it ${db.cacheBacked[it] ?: 0}" }}"
                lines += "  tombstones:        ${db.tombstones}"
                lines += "  resumable scan in progress: ${if (db.scanInProgress) "yes" else "no"}"
            }
            lines += ""
            lines += "3. Local bytes"
            lines += "  sync_root:         ${report.syncRootTree.files} files, ${formatBytesBinary(report.syncRootTree.bytes)}" +
                unreadable(report.syncRootTree.unreadable)
            lines += "  hydration cache:   ${report.cacheTree.files} files, ${formatBytesBinary(report.cacheTree.bytes)}" +
                unreadable(report.cacheTree.unreadable)
            for (cls in PathClass.entries) {
                val n = report.classCounts[cls] ?: 0
                lines += "  ${cls.label.padEnd(26)} ${n.toString().padStart(8)}  ${formatBytesBinary(report.classBytes[cls] ?: 0L)}"
            }
            val untracked = report.files.filter { it.cls == PathClass.UNTRACKED }
            lines += "    of which excluded: ${untracked.count { it.excluded }}, outside sync_path: ${untracked.count { it.outOfScope }}, " +
                "deleted in state.db: ${untracked.count { it.tombstoned }}"
            lines += ""
            lines += "4. Staging and recovery files (names and sizes only)"
            lines += "  Internxt upload tombstones: ${report.staging.uploadTombstoneFiles} files, ${formatBytesBinary(report.staging.uploadTombstoneBytes)}"
            lines += "  cache staging temps:        ${report.staging.cacheTempFiles} files, ${formatBytesBinary(report.staging.cacheTempBytes)}"
            lines += "  profile dir:"
            report.profileEntries.forEach { lines += "      ${entry(it)}" }
            lines +=
                when {
                    i.clientStateDir == null -> "  client recovery files: not checked (no client state dir on this platform)"
                    report.clientFiles.isEmpty() -> "  client recovery files: none in ${i.clientStateDir}"
                    else -> "  client recovery files in ${i.clientStateDir}:"
                }
            report.clientFiles.forEach { lines += "      ${entry(it)}" }
            lines += ""
            lines += "5. Summary"
            val risky = report.atRisk
            lines += "  paths at risk in a careless conversion: ${risky.size}"
            val shown = if (detail) risky else risky.take(TEXT_PATH_LIMIT)
            shown.forEach { lines += "      ${it.path}  [${it.risks.joinToString("; ")}]" }
            if (shown.size < risky.size) lines += "      ... ${risky.size - shown.size} more (-v lists all, --json has every path)"
            lines += "  Nothing was changed: no write to state.db or the profile, no file moved, no network."
            return lines.joinToString("\n")
        }

        private fun unreadable(n: Int) = if (n > 0) " ($n unreadable)" else ""

        private fun entry(e: LegacyInventory.SizedEntry) =
            if (e.isDirectory) "${e.name}/  ${e.files} files, ${formatBytesBinary(e.bytes)}" else "${e.name}  ${formatBytesBinary(e.bytes)}"

        private fun strings(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })

        private fun nullable(v: Any?): JsonElement =
            when (v) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString().lowercase())
            }

        private fun sized(list: List<LegacyInventory.SizedEntry>) =
            JsonArray(
                list.map {
                    buildJsonObject {
                        put("name", it.name)
                        put("directory", it.isDirectory)
                        put("bytes", it.bytes)
                        put("files", it.files)
                    }
                },
            )

        internal fun renderJson(report: LegacyInventory.Report): String {
            val i = report.inputs
            val db = report.db
            val root =
                buildJsonObject {
                    put("version", 1)
                    put(
                        "profile",
                        buildJsonObject {
                            put("name", i.profileName)
                            put("provider", i.providerType)
                            put("profile_dir", i.profileDir.toString())
                            put("sync_root", i.syncRoot.toString())
                            put("sync_path", strings(i.syncPaths))
                            put("cache_dir", i.cacheDir.toString())
                            put("exclude_patterns", strings(i.excludePatterns))
                            put("config", JsonObject(i.configFacts.associate { (k, v) -> k to JsonPrimitive(v) }))
                            put("content_hash", report.contentHash?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("lock_file_present", report.lock.lockFilePresent)
                            put("stale_lock_pid", report.lock.recordedHolderPid?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                    put(
                        "state",
                        if (db == null) {
                            JsonNull
                        } else {
                            buildJsonObject {
                                put("schema_version", db.schemaVersion?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("rows_by_status", JsonObject(db.rowsByStatus.mapValues { JsonPrimitive(it.value) }))
                                put("alive_files", db.aliveFiles)
                                put("alive_folders", db.aliveFolders)
                                put("local_id_rows", db.localIdRows)
                                put("pending_uploads", strings(db.pendingUploads))
                                put("pending_mount_replayable", strings(db.pendingMountReplayable))
                                put("pending_mirror", strings(db.pendingMirror))
                                put("pending_without_bytes", strings(db.pendingWithoutBytes))
                                put("local_unhydrated", strings(db.localUnhydrated))
                                put("upload_refused", strings(db.uploadRefused))
                                put("last_error_at", strings(db.lastErrorAt))
                                put("download_quarantined", db.downloadQuarantined)
                                put("cache_backed", JsonObject(db.cacheBacked.mapValues { JsonPrimitive(it.value) }))
                                put("tombstones", db.tombstones)
                                put("scan_in_progress", db.scanInProgress)
                            }
                        },
                    )
                    put(
                        "local",
                        buildJsonObject {
                            put("sync_root_files", report.syncRootTree.files)
                            put("sync_root_bytes", report.syncRootTree.bytes)
                            put("sync_root_unreadable", report.syncRootTree.unreadable)
                            put("cache_files", report.cacheTree.files)
                            put("cache_bytes", report.cacheTree.bytes)
                            put("cache_unreadable", report.cacheTree.unreadable)
                            put(
                                "classes",
                                JsonObject(
                                    PathClass.entries.associate { cls ->
                                        cls.name.lowercase() to
                                            buildJsonObject {
                                                put("paths", report.classCounts[cls] ?: 0)
                                                put("bytes", report.classBytes[cls] ?: 0L)
                                            }
                                    },
                                ),
                            )
                            put(
                                "paths",
                                JsonArray(
                                    report.files.map { r ->
                                        buildJsonObject {
                                            put("path", r.path)
                                            put("class", r.cls.name.lowercase())
                                            put("row", nullable(r.rowKind))
                                            put("hydrated", nullable(r.hydrated))
                                            put("cache_backed", nullable(r.cacheBacked))
                                            put("sync_root_bytes", nullable(r.syncRootBytes))
                                            put("cache_bytes", nullable(r.cacheBytes))
                                            put("sync_root_vs_cloud", nullable(r.syncRootMatch))
                                            put("cache_vs_cloud", nullable(r.cacheMatch))
                                            put("excluded", r.excluded)
                                            put("out_of_scope", r.outOfScope)
                                            put("tombstoned", r.tombstoned)
                                            put("risks", strings(r.risks))
                                        }
                                    },
                                ),
                            )
                        },
                    )
                    put(
                        "staging",
                        buildJsonObject {
                            put("upload_tombstone_files", report.staging.uploadTombstoneFiles)
                            put("upload_tombstone_bytes", report.staging.uploadTombstoneBytes)
                            put("cache_temp_files", report.staging.cacheTempFiles)
                            put("cache_temp_bytes", report.staging.cacheTempBytes)
                            put("profile_dir", sized(report.profileEntries))
                            put("client_state_dir", i.clientStateDir?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                            put("client_files", sized(report.clientFiles))
                        },
                    )
                    put(
                        "summary",
                        buildJsonObject {
                            put("at_risk", strings(report.atRisk.map { it.path }))
                            put("nothing_changed", true)
                        },
                    )
                }
            return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root)
        }
    }
}
