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
        ratePerS?.let { put("rate_per_s", (it * 10).roundToLong() / 10.0) }
        etaS?.let { put("eta_s", it) }
        etaKind?.let { put("eta_kind", it.wire) }
        lastSuccessAtMs?.let { put("last_success_at_ms", it) }
        lastScanComplete?.let { put("last_scan_complete", it) }
        lastError?.let { put("last_error", it) }
        nextAttemptAtMs?.let { put("next_attempt_at_ms", it) }
    }
