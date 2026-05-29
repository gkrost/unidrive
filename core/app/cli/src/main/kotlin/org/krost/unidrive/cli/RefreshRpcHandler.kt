package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * IPC verb handler for `refresh.run` (spec §4.2 of unidrive-daemon-design.md).
 *
 * Synchronous reply: `{"ok":true,"job_id":"<uuid>"}` immediately, or
 * `{"ok":false,"error":"busy",...}` if another refresh is in flight.
 *
 * Progress + terminal events are streamed post-reply via the IpcServer's
 * subscriber-set mechanism (sync.subscribe fan-out, mechanism β).
 *
 * Invariant I6: at most one refresh.run in flight per daemon. Serialised
 * via [inFlight]; a second concurrent call returns "busy".
 *
 * Terminal event delivery is best-effort per spec §5.2 F9 — if IpcServer
 * close races the emit, the event is dropped silently. Clients (the
 * `unidrive refresh` CLI) treat socket EOF as equivalent to a
 * `refresh.done error=shutdown` event.
 */
class RefreshRpcHandler(
    private val ipcServer: IpcServer,
    private val engine: SyncEngine,
    private val db: StateDatabase,
    private val scope: CoroutineScope,
    // When this returns true a mount client is serving the profile's view from
    // state.db, so refresh must take the one-way enumerate path (no sync_root
    // scan, no deletion guards) instead of the legacy bidirectional reconcile.
    // See docs/dev/specs/mount-view-refresh-design.md §4.
    private val mountClientConnected: () -> Boolean = { false },
    // Terminal-event sink. Defaults to the broadcast channel; injectable for tests.
    private val emit: (String) -> Unit = ipcServer::emit,
) {
    private val log = LoggerFactory.getLogger(RefreshRpcHandler::class.java)

    private val inFlight = AtomicReference<RefreshJob?>(null)

    data class RefreshJob(val id: String, val job: Job)

    /**
     * Handle one `refresh.run` request. Returns the synchronous reply JSON.
     * If accepted (ok:true), launches the refresh body as a child of [scope].
     */
    fun handle(@Suppress("UNUSED_PARAMETER") connectionId: String, jsonRequest: String): String {
        val jobId = UUID.randomUUID().toString()
        // Reserve the in-flight slot BEFORE launching: a placeholder job is swapped
        // in atomically, so a second concurrent request can never slip through the
        // gap between launch and registration regardless of dispatcher. If another
        // refresh already holds the slot, reject as busy without launching.
        val placeholder = RefreshJob(jobId, Job())
        if (!inFlight.compareAndSet(null, placeholder)) {
            val existing = inFlight.get()
            return """{"ok":false,"error":"busy","message":"refresh already running (job_id=${existing?.id})"}"""
        }
        // Parse optional `"reset": true` from the request. Spec amendment vs.
        // initial §4.2 ("no parameters"): F9 added `reset` to cover the
        // delta-cursor recovery path that was lost when RefreshCommand
        // dropped SyncCommand inheritance in Task 11. JSON parse is intentionally
        // minimal (regex over the request body) — the daemon RPC contract is
        // append-only so a sloppy match here can't break a strict client.
        val reset = RESET_TRUE_REGEX.containsMatchIn(jsonRequest)
        val forceDelete = FORCE_DELETE_TRUE_REGEX.containsMatchIn(jsonRequest)
        val mounted = mountClientConnected()
        val launched =
            try {
                scope.launch {
                    val terminalEvent = try {
                        if (mounted) {
                            // Mount-view refresh: one-way remote→state.db. enumerateRemoteIntoState
                            // handles `reset` internally (clears only the cursor — NOT db.resetAll,
                            // which would empty the view if the gather then failed). force_delete is
                            // meaningless here (enumerate cannot delete remote content), so surface
                            // that it was ignored rather than dropping it silently (review gap 5).
                            log.info("refresh.run routed to enumerate (mount client connected): job_id=$jobId reset=$reset")
                            // enumerateRemoteIntoState converts provider failures into EnumerateResult(ok=false)
                            // rather than throwing, so a false result must be surfaced — not emitted as success.
                            val result = engine.enumerateRemoteIntoState(reset)
                            if (result.ok) {
                                val forceDeleteIgnored = if (forceDelete) ""","force_delete_ignored":true""" else ""
                                """{"event":"refresh.done","job_id":"$jobId","ok":true$forceDeleteIgnored}"""
                            } else {
                                log.warn("refresh.run enumerate failed: job_id=$jobId error=${result.error}")
                                val msg = result.error?.replace("\"", "\\\"") ?: ""
                                """{"event":"refresh.done","job_id":"$jobId","ok":false,"error":"provider_error","message":"$msg"}"""
                            }
                        } else {
                            if (reset) {
                                log.info("refresh.run reset=true: clearing state.db before re-enumerating: job_id=$jobId")
                                db.resetAll()
                            }
                            engine.syncOnce(skipTransfers = true, skipRemoteGather = false)
                            """{"event":"refresh.done","job_id":"$jobId","ok":true}"""
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        log.info("refresh.run cancelled (shutdown): job_id=$jobId")
                        """{"event":"refresh.done","job_id":"$jobId","ok":false,"error":"shutdown"}"""
                    } catch (e: Exception) {
                        log.warn("refresh.run failed: job_id=$jobId", e)
                        val msg = e.message?.replace("\"", "\\\"") ?: ""
                        """{"event":"refresh.done","job_id":"$jobId","ok":false,"error":"provider_error","message":"$msg"}"""
                    } finally {
                        inFlight.set(null)
                    }
                    // Fan terminal event to all sync.subscribe subscribers via the
                    // existing broadcast channel. emit() is non-blocking trySend; the
                    // broadcastJob loop in IpcServer filters by syncSubscribers set.
                    runCatching { emit(terminalEvent) }
                }
            } catch (t: Throwable) {
                // launch itself failed (e.g. scope already cancelled): release the
                // reserved slot so the guard can never be left stuck holding the
                // placeholder, then surface the failure to the caller.
                inFlight.compareAndSet(placeholder, null)
                throw t
            }
        // Swap the placeholder for the real job, but only if the launched coroutine
        // hasn't already finished and cleared the slot to null — a CAS avoids
        // resurrecting a stale in-flight entry (which would wrongly reject the next
        // request as busy and make awaitInFlight join a completed job).
        inFlight.compareAndSet(placeholder, RefreshJob(jobId, launched))
        return """{"ok":true,"job_id":"$jobId"}"""
    }

    fun isInFlight(): Boolean = inFlight.get() != null
    fun inFlightJobId(): String? = inFlight.get()?.id

    suspend fun awaitInFlight() {
        inFlight.get()?.job?.join()
    }

    companion object {
        private val RESET_TRUE_REGEX = Regex("\"reset\"\\s*:\\s*true")
        private val FORCE_DELETE_TRUE_REGEX = Regex("\"force_delete\"\\s*:\\s*true")
    }
}
