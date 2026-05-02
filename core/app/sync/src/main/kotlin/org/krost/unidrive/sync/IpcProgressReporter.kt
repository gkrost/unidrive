package org.krost.unidrive.sync

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class IpcProgressReporter(
    private val server: IpcServer,
    private val profileName: String,
) : ProgressReporter {
    private fun emit(
        event: String,
        block: JsonObjectBuilder.() -> Unit,
    ) {
        val json =
            buildJsonObject {
                put("event", event)
                put("profile", profileName)
                block()
                put("timestamp", Instant.now().toString())
            }
        server.emit(json.toString())
    }

    fun emitSyncStarted() {
        server.updateState(IpcServer.SyncState(profile = profileName))
        emit("sync_started") {}
    }

    fun emitSyncError(message: String) {
        val sanitized = message.replace("\n", " ").take(500)
        emit("sync_error") {
            put("message", sanitized)
        }
    }

    fun emitPollState(
        state: String,
        intervalSeconds: Int,
        idleCycles: Int,
    ) {
        emit("poll_state") {
            put("state", state)
            put("interval_s", intervalSeconds)
            put("idle_cycles", idleCycles)
        }
    }

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {
        val current = server.syncState ?: IpcServer.SyncState(profile = profileName)
        server.updateState(current.copy(phase = phase, scanCount = count))
        emit("scan_progress") {
            put("phase", phase)
            put("count", count)
        }
    }

    override fun onReconcileProgress(
        processed: Int,
        total: Int,
    ) {
        // UD-240g: reconcile-phase heartbeat. UI clients can render a progress
        // bar from (processed / total) and stop showing "stuck on local scan".
        val current = server.syncState ?: IpcServer.SyncState(profile = profileName)
        server.updateState(current.copy(phase = "reconcile", scanCount = processed))
        emit("reconcile_progress") {
            put("processed", processed)
            put("total", total)
        }
    }

    override fun onActionCount(total: Int) {
        val current = server.syncState ?: IpcServer.SyncState(profile = profileName)
        server.updateState(current.copy(actionTotal = total))
        emit("action_count") {
            put("total", total)
        }
    }

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {
        val current = server.syncState ?: IpcServer.SyncState(profile = profileName)
        server.updateState(current.copy(actionIndex = index, actionTotal = total, lastAction = action, lastPath = path))
        emit("action_progress") {
            put("index", index)
            put("total", total)
            put("action", action)
            put("path", path)
        }
    }

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        emit("transfer_progress") {
            put("path", path)
            put("bytes_transferred", bytesTransferred)
            put("total_bytes", totalBytes)
        }
    }

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        val current = server.syncState ?: IpcServer.SyncState(profile = profileName)
        server.updateState(
            current.copy(
                isComplete = true,
                downloaded = downloaded,
                uploaded = uploaded,
                conflicts = conflicts,
                durationMs = durationMs,
            ),
        )
        emit("sync_complete") {
            put("downloaded", downloaded)
            put("uploaded", uploaded)
            put("conflicts", conflicts)
            put("duration_ms", durationMs)
        }
    }

    override fun onWarning(message: String) {
        val sanitized = message.replace("\n", " ").take(500)
        emit("warning") {
            put("message", sanitized)
        }
    }
}
