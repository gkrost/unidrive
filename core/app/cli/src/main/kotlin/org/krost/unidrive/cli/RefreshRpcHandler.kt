package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.krost.unidrive.sync.IpcServer
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
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(RefreshRpcHandler::class.java)

    private val inFlight = AtomicReference<RefreshJob?>(null)

    data class RefreshJob(val id: String, val job: Job)

    /**
     * Handle one `refresh.run` request. Returns the synchronous reply JSON.
     * If accepted (ok:true), launches the refresh body as a child of [scope].
     */
    fun handle(@Suppress("UNUSED_PARAMETER") connectionId: String, @Suppress("UNUSED_PARAMETER") jsonRequest: String): String {
        val existing = inFlight.get()
        if (existing != null) {
            return """{"ok":false,"error":"busy","message":"refresh already running (job_id=${existing.id})"}"""
        }
        val jobId = UUID.randomUUID().toString()
        val launched = scope.launch {
            val terminalEvent = try {
                engine.syncOnce(skipTransfers = true, skipRemoteGather = false)
                """{"event":"refresh.done","job_id":"$jobId","ok":true}"""
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
            runCatching { ipcServer.emit(terminalEvent) }
        }
        inFlight.set(RefreshJob(jobId, launched))
        return """{"ok":true,"job_id":"$jobId"}"""
    }

    fun isInFlight(): Boolean = inFlight.get() != null
    fun inFlightJobId(): String? = inFlight.get()?.id
}
