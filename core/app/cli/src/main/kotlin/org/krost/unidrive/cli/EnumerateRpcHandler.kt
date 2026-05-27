package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * IPC verb handler for `sync.enumerate` (docs/dev/specs/mount-view-refresh-design.md §4.1):
 * a one-way remote→state.db refresh for view consumers (the FUSE mount). Mirrors
 * [RefreshRpcHandler]'s in-flight + post-reply-terminal-event shape.
 *
 * Synchronous reply: `{"ok":true,"job_id":"<uuid>"}` immediately, or
 * `{"ok":false,"error":"busy",...}` if another enumeration is in flight.
 *
 * Terminal event (`enumerate.done`) is emitted post-reply via [emit] (the daemon
 * wires `IpcServer::emit`). At most one enumeration per daemon at a time —
 * serialised via [inFlight] with a compareAndSet-before-launch guard so a second
 * concurrent request can never slip through the gap between launch and registration.
 */
class EnumerateRpcHandler(
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
    private val emit: (String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(EnumerateRpcHandler::class.java)

    private val inFlight = AtomicReference<EnumerateJob?>(null)

    data class EnumerateJob(val id: String, val job: Job)

    fun handle(@Suppress("UNUSED_PARAMETER") connectionId: String, jsonRequest: String): String {
        val jobId = UUID.randomUUID().toString()
        // Reserve the in-flight slot BEFORE launching (BACKLOG-64 defensive note):
        // a placeholder job is swapped in atomically; if another enumeration already
        // holds the slot, reject as busy without launching.
        val placeholder = EnumerateJob(jobId, Job())
        if (!inFlight.compareAndSet(null, placeholder)) {
            val existing = inFlight.get()
            return """{"ok":false,"error":"busy","message":"enumeration already running (job_id=${existing?.id})"}"""
        }
        val reset = RESET_TRUE_REGEX.containsMatchIn(jsonRequest)
        val launched = scope.launch {
            val terminalEvent =
                try {
                    val result = engine.enumerateRemoteIntoState(reset)
                    if (result.ok) {
                        buildJsonObject {
                            put("event", "enumerate.done")
                            put("job_id", jobId)
                            put("ok", true)
                            put("upserted", result.upserted)
                            put("reaped", result.reaped)
                            put("complete", result.complete)
                        }.toString()
                    } else {
                        buildJsonObject {
                            put("event", "enumerate.done")
                            put("job_id", jobId)
                            put("ok", false)
                            put("error", "provider_error")
                            put("message", result.error ?: "")
                        }.toString()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    log.info("sync.enumerate cancelled (shutdown): job_id=$jobId")
                    buildJsonObject {
                        put("event", "enumerate.done")
                        put("job_id", jobId)
                        put("ok", false)
                        put("error", "shutdown")
                    }.toString()
                } catch (e: Exception) {
                    log.warn("sync.enumerate failed: job_id=$jobId", e)
                    buildJsonObject {
                        put("event", "enumerate.done")
                        put("job_id", jobId)
                        put("ok", false)
                        put("error", "provider_error")
                        put("message", e.message ?: "")
                    }.toString()
                } finally {
                    inFlight.set(null)
                }
            runCatching { emit(terminalEvent) }
        }
        // Swap the placeholder for the real job, but only if the launched coroutine
        // hasn't already finished and cleared the slot to null — a CAS avoids
        // resurrecting a stale in-flight entry (which would wrongly reject the next
        // request as busy and make awaitInFlight join a completed job).
        inFlight.compareAndSet(placeholder, EnumerateJob(jobId, launched))
        return """{"ok":true,"job_id":"$jobId"}"""
    }

    /**
     * Run one enumeration through the SAME in-flight guard the `sync.enumerate`
     * verb uses (mount-view-refresh-design.md §5). Returns the result, or null
     * if an enumeration (manual or another poll tick) already holds the guard —
     * the caller (the poll loop) skips this tick rather than overlapping.
     * No terminal event is emitted (this is the in-process poll path, not an
     * RPC reply).
     */
    suspend fun runGuarded(reset: Boolean): EnumerateResult? {
        val jobId = UUID.randomUUID().toString()
        val placeholder = EnumerateJob(jobId, Job())
        if (!inFlight.compareAndSet(null, placeholder)) return null
        return try {
            engine.enumerateRemoteIntoState(reset)
        } finally {
            inFlight.set(null)
        }
    }

    fun isInFlight(): Boolean = inFlight.get() != null

    fun inFlightJobId(): String? = inFlight.get()?.id

    /** Test hook: join the currently-launched enumeration job, if any. */
    suspend fun awaitInFlight() {
        inFlight.get()?.job?.join()
    }

    companion object {
        private val RESET_TRUE_REGEX = Regex("\"reset\"\\s*:\\s*true")
    }
}
