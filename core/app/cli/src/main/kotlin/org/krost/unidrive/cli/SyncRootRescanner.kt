package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * #504: the daemon's safety-net rescan of the sync root. One pass at start, then one every
 * [intervalMs]; [intervalMs] <= 0 turns the whole thing off (no start pass either). [pass] is
 * `MountEngine.rescanSyncRootForUpload` (#560 U3): upload-only, never downloads or deletes. A
 * failing pass is logged and the loop goes on, so one bad cycle (offline, auth blip) never ends
 * the safety net.
 * [run] suspends for the daemon's lifetime and ends only when its scope is cancelled.
 */
class SyncRootRescanner(
    private val intervalMs: Long,
    private val pass: suspend () -> Unit,
) {
    private val log = LoggerFactory.getLogger(SyncRootRescanner::class.java)

    suspend fun run() {
        if (intervalMs <= 0) return
        while (true) {
            try {
                pass()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("sync root rescan failed; next attempt in {} ms", intervalMs, e)
            }
            delay(intervalMs)
        }
    }
}
