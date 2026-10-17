package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.EnumerationStatus
import kotlin.math.roundToLong

/**
 * The `enumeration` object of the `daemon.status` reply (docs/dev/specs/unidrive-daemon-design.md §4.3).
 * Only `state`, `first` and `attempt` are always there; what is unknown is left out.
 */
internal fun EnumerationStatus.toJson(): JsonObject =
    buildJsonObject {
        put("state", state.wire)
        put("first", first)
        put("attempt", attempt)
        phase?.let { put("phase", it.wire) }
        listing?.let { put("listing", it) }
        startedAtMs?.let { put("started_at_ms", it) }
        elapsedMs?.let { put("elapsed_ms", it) }
        items?.let { put("items", it) }
        foldersDone?.let { put("folders_done", it) }
        foldersKnown?.let { put("folders_known", it) }
        foldersSkipped?.let { put("folders_skipped", it) }
        pathsDone?.let { put("paths_done", it) }
        pathsTotal?.let { put("paths_total", it) }
        ratePerS?.let { put("rate_per_s", (it * 10).roundToLong() / 10.0) }
        etaS?.let { put("eta_s", it) }
        etaKind?.let { put("eta_kind", it.wire) }
        lastSuccessAtMs?.let { put("last_success_at_ms", it) }
        lastScanComplete?.let { put("last_scan_complete", it) }
        // Absent when none: the items of the last full listing below a folder that is gone for good.
        unreachableItems?.let { put("unreachable_items", it) }
        // Absent when none: the tracked items the last full listing did not return because it ended incomplete.
        droppedItems?.let { put("dropped_items", it) }
        // Present (true) only while a running attempt lists the drive again because the last scan was incomplete.
        if (recovery) put("recovery", true)
        lastError?.let { put("last_error", it) }
        // #878: a machine-readable cause (http_408, timeout, auth_rejected); absent when unknown.
        lastErrorCode?.let { put("last_error_code", it) }
        nextAttemptAtMs?.let { put("next_attempt_at_ms", it) }
    }
