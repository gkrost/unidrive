package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Translates IpcServer JSON-line requests to Hydration verb calls
 * and back. Registers six verbs on the IpcServer; the per-connection
 * id used in the Hydration API is the IpcServer's per-client opaque
 * connection identifier (passed by the caller of handle()).
 *
 * Wire format (all JSON):
 *   open_read   request:  {"verb":"hydration.open_read","handle_id":"...","path":"/foo"}
 *   open_read   reply ok: {"ok":true,"cache_path":"/home/.../foo.txt"}
 *   open_read   reply err:{"ok":false,"error":"<message>"}
 *   open_write  request:  {"verb":"hydration.open_write","handle_id":"...","path":"/foo","cache_path":"/home/.../foo.txt"}
 *   open_write  reply:    same as open_read
 *   close_handle request: {"verb":"hydration.close_handle","handle_id":"..."}
 *   close_handle reply:   {"ok":true}
 *   hydrate     request:  {"verb":"hydration.hydrate","path":"/foo"}
 *   hydrate     reply:    {"ok":true} or {"ok":false,"error":"<message>"}
 *   dehydrate   request:  {"verb":"hydration.dehydrate","path":"/foo"}
 *   dehydrate   reply:    {"ok":true} or {"ok":false,"error":"busy"} or {"ok":false,"error":"<message>"}
 *   mkdir       request:  {"verb":"hydration.mkdir","path":"/foo"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   unlink      request:  {"verb":"hydration.unlink","path":"/foo.txt"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_is_folder"}     EISDIR
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rmdir       request:  {"verb":"hydration.rmdir","path":"/foo"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_is_file"}       ENOTDIR
 *                         {"ok":false,"error":"not_empty"}          ENOTEMPTY
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   create      request:  {"verb":"hydration.create","handle_id":"...","path":"/foo.txt"}
 *               reply:    {"ok":true,"cache_path":"/home/.../foo.txt","handle_id":"..."}
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"path_exists"}        EEXIST
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rename      request:  {"verb":"hydration.rename","old_path":"/a","new_path":"/b"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"old_path_not_found"}    ENOENT
 *                         {"ok":false,"error":"new_parent_not_found"}  ENOENT
 *                         {"ok":false,"error":"new_path_exists"}       EEXIST
 *                         {"ok":false,"error":"<msg>"}                 EIO
 *
 *   open_write_begin request: {"verb":"hydration.open_write_begin","path":"/foo"}  [,"handle_id":"wh-N"]  reply ok: {"ok":true,"cache_path":"..."}  errs: unknown_path / path_is_folder
 *                            handle_id is OPTIONAL: present → registers a JVM open-set entry (O_TRUNC live open);
 *                            absent → no registration (one-shot setattr/bare-truncate, backward-compatible).
 *
 *   subscribe   request:  {"verb":"hydration.subscribe"}
 *   subscribe   reply:    {"ok":true} — and from then on, the connection becomes a one-way
 *                         event stream (server-pushed NDJSON of HydrationEvent serializations)
 *
 * Subscribe pipeline: each subscribed connection gets its own bounded queue
 * (capacity 64). The single fan-out site (dispatchEvent) deals events to all
 * subscribers; per-subscriber writer coroutines drain their queue and push
 * NDJSON lines via the writer function. When a subscriber's queue is full the
 * dispatcher drops the OLDEST queued event and bumps a per-subscriber lost
 * counter; the writer emits one `{"event":"lost","since_last":N}` sentinel
 * line ahead of the next deliverable event and resets the counter. The whole
 * registry plus writer-coroutine teardown is owned by this handler;
 * onSubscriberDisconnect(connectionId) is the production hook called from
 * IpcServer's connection-close listener.
 */
class HydrationIpcHandler(
    private val hydration: Hydration,
) {
    private data class Subscriber(
        val queue: Channel<HydrationEvent>,
        val dropped: AtomicInteger,
        val job: Job,
    )

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private var subscriberScope: CoroutineScope? = null
    private var subscriberWriter: (suspend (String, String) -> Boolean)? = null

    /**
     * True when at least one connection has issued `hydration.subscribe` — i.e. a
     * mount client (the FUSE co-daemon) is serving this profile's view. The daemon
     * uses this to route `refresh.run` to the one-way enumerate path rather than the
     * legacy sync_root reconcile (mount-view-refresh-design.md §4).
     */
    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    /**
     * Production wiring entry point. Stores the scope used to launch per-subscriber
     * writer coroutines and the function used to write a single NDJSON line to a
     * given connection. The daemon also registers onSubscriberDisconnect on the
     * IpcServer's connection-close listener so subscribers tear down when their
     * UDS connection drops.
     */
    fun start(scope: CoroutineScope, writer: suspend (connectionId: String, line: String) -> Boolean) {
        subscriberScope = scope
        subscriberWriter = writer
    }

    /**
     * Fan an event out to every registered subscriber. Non-suspending: each
     * subscriber's bounded queue is fed via trySend; on overflow the oldest
     * unsent event is drained and the subscriber's dropped counter increments.
     * The single collector at the caller site drains hydration.events into here.
     */
    fun dispatchEvent(event: HydrationEvent) {
        for (sub in subscribers.values) {
            offerWithDropOldest(sub, event)
        }
    }

    private fun offerWithDropOldest(sub: Subscriber, event: HydrationEvent) {
        while (true) {
            val r = sub.queue.trySend(event)
            if (r.isSuccess) return
            if (r.isClosed) return
            // Buffer full: drain one (the oldest) and retry. A concurrent writer
            // drain shrinks the buffer too — either path frees a slot.
            val drained = sub.queue.tryReceive()
            if (drained.isSuccess) sub.dropped.incrementAndGet()
            // If tryReceive itself failed (race with writer just emptied it), loop
            // and retry trySend — the buffer now has room.
        }
    }

    /**
     * Tear down a subscriber: cancel its writer coroutine, close its queue, drop
     * the registry entry. Idempotent — disconnect for a non-subscribed connection
     * is a no-op (broadcast clients trigger close listeners too, and only some of
     * them are subscribers).
     */
    fun onSubscriberDisconnect(connectionId: String) {
        val sub = subscribers.remove(connectionId) ?: return
        sub.job.cancel()
        sub.queue.close()
    }

    private fun registerSubscriber(connectionId: String) {
        val scope = subscriberScope ?: return  // pre-start() subscribe: caller didn't wire the pipeline
        val writer = subscriberWriter ?: return
        // Repeat-subscribe on the same connection: tear down the prior subscriber first
        // so we never have two writer coroutines competing on the same connection.
        subscribers.remove(connectionId)?.let { it.job.cancel(); it.queue.close() }
        val queue = Channel<HydrationEvent>(capacity = SUBSCRIBER_QUEUE_CAPACITY)
        val dropped = AtomicInteger(0)
        val job = scope.launch {
            try {
                for (event in queue) {
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) {
                        val ok = writer(connectionId, """{"event":"lost","since_last":$lost}""")
                        if (!ok) break
                    }
                    val ok = writer(connectionId, serialiseHydrationEvent(event))
                    if (!ok) break
                }
            } finally {
                subscribers.remove(connectionId)
                queue.close()
            }
        }
        subscribers[connectionId] = Subscriber(queue, dropped, job)
    }

    companion object {
        // Per-subscriber bounded queue capacity. Matches the MutableSharedFlow's
        // extraBufferCapacity in HydrationImpl; the SharedFlow is the upstream
        // source and the queue is the per-subscriber smoothing layer.
        const val SUBSCRIBER_QUEUE_CAPACITY = 64

        // Single source of truth for the verbs this handler answers. The daemon
        // (SyncCommand) iterates this list to wire each verb on IpcServer via
        // registerHandler. The dispatch table in `handle()` below must stay in
        // lockstep — there's a unit test pinning the two together.
        val VERBS: List<String> = listOf(
            "hydration.open_read",
            "hydration.open_write",
            "hydration.open_write_begin",
            "hydration.close_handle",
            "hydration.hydrate",
            "hydration.dehydrate",
            "hydration.subscribe",
            "hydration.last_synced",
            "hydration.list",
            "hydration.mkdir",
            "hydration.unlink",
            "hydration.rmdir",
            "hydration.create",
            "hydration.rename",
        )
    }
    suspend fun handle(connectionId: String, jsonRequest: String): String {
        val verb = pluck(jsonRequest, "verb") ?: return reply(ok = false, error = "missing_verb")
        return when (verb) {
            "hydration.open_read" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.openForRead(connectionId, handleId, path)) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                val cache = pluck(jsonRequest, "cache_path") ?: return reply(ok = false, error = "missing_cache_path")
                if (cache.isEmpty()) return reply(ok = false, error = "missing_cache_path")
                when (val r = hydration.openForWrite(connectionId, handleId, path, Paths.get(cache))) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write_begin" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                // handle_id is OPTIONAL: present → O_TRUNC live open (registers open-set entry);
                // absent → one-shot setattr/bare-truncate (no registration).
                val handleId = pluck(jsonRequest, "handle_id")
                when (val r = hydration.openWriteBegin(connectionId, path, handleId)) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.close_handle" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                hydration.closeHandle(connectionId, handleId)
                reply(ok = true)
            }
            "hydration.hydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.hydrate(path)) {
                    HydrateResult.Ok -> reply(ok = true)
                    is HydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.dehydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.dehydrate(path)) {
                    DehydrateResult.Ok -> reply(ok = true)
                    DehydrateResult.Busy -> reply(ok = false, error = "busy")
                    is DehydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.last_synced" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.lastSynced(path)) {
                    is LastSyncedResult.Ok -> """{"ok":true,"mtime_ms":${r.mtimeEpochMillis}}"""
                    is LastSyncedResult.Unknown -> reply(ok = false, error = r.reason)
                }
            }
            "hydration.list" -> {
                val prefix = pluck(jsonRequest, "prefix") ?: return reply(ok = false, error = "missing_prefix")
                when (val r = hydration.list(prefix)) {
                    is ListResult.Ok -> serialiseListEntries(r.entries)
                    is ListResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.mkdir" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.mkdir(path)) {
                    is MkdirResult.Ok -> reply(ok = true)
                    MkdirResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    is MkdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.unlink" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.unlink(path)) {
                    is UnlinkResult.Ok -> reply(ok = true)
                    UnlinkResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
                    is UnlinkResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rmdir" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.rmdir(path)) {
                    is RmdirResult.Ok -> reply(ok = true)
                    RmdirResult.PathIsFile -> reply(ok = false, error = "path_is_file")
                    RmdirResult.NotEmpty -> reply(ok = false, error = "not_empty")
                    is RmdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.create" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.create(connectionId, handleId, path)) {
                    is CreateResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"handle_id":${jsonEsc(r.handleId)}}"""
                    CreateResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    CreateResult.PathExists -> reply(ok = false, error = "path_exists")
                    is CreateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rename" -> {
                val oldPath = pluck(jsonRequest, "old_path") ?: return reply(ok = false, error = "missing_old_path")
                val newPath = pluck(jsonRequest, "new_path") ?: return reply(ok = false, error = "missing_new_path")
                when (val r = hydration.rename(oldPath, newPath)) {
                    is RenameResult.Ok -> reply(ok = true)
                    RenameResult.OldPathNotFound -> reply(ok = false, error = "old_path_not_found")
                    RenameResult.NewParentNotFound -> reply(ok = false, error = "new_parent_not_found")
                    RenameResult.NewPathExists -> reply(ok = false, error = "new_path_exists")
                    is RenameResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.subscribe" -> {
                registerSubscriber(connectionId)
                reply(ok = true)
            }
            else -> reply(ok = false, error = "unknown_verb")
        }
    }

    private fun reply(ok: Boolean, error: String? = null): String =
        if (ok) """{"ok":true}""" else """{"ok":false,"error":${jsonEsc(error ?: "unknown")}}"""

    // Minimal JSON pluck — works for flat top-level string fields. Sufficient
    // for our verb messages; we don't accept arbitrary client JSON shapes.
    private fun pluck(line: String, key: String): String? {
        val needle = "\"$key\""
        val k = line.indexOf(needle)
        if (k < 0) return null
        val colon = line.indexOf(':', k + needle.length)
        if (colon < 0) return null
        val q1 = line.indexOf('"', colon)
        if (q1 < 0) return null
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < line.length) { sb.append(line[i + 1]); i += 2; continue }
            sb.append(c); i++
        }
        return null
    }
}

/**
 * Serialise a HydrationEvent to a JSON-line string. Called by the
 * per-subscriber writer coroutine inside HydrationIpcHandler.
 */
private fun serialiseListEntries(entries: List<ListResult.Entry>): String {
    val sb = StringBuilder("""{"ok":true,"entries":[""")
    for ((i, e) in entries.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("{\"path\":").append(jsonEsc(e.path))
            .append(",\"size\":").append(e.size)
            .append(",\"mtime_ms\":").append(e.mtimeEpochMillis)
            .append(",\"hydrated\":").append(e.isHydrated)
            .append(",\"folder\":").append(e.isFolder)
            .append('}')
    }
    sb.append("]}")
    return sb.toString()
}

fun serialiseHydrationEvent(e: HydrationEvent): String = when (e) {
    is HydrationEvent.Hydrating  -> """{"event":"hydrating","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Hydrated   -> """{"event":"hydrated","path":${jsonEsc(e.path)},"bytes":${e.bytes}}"""
    is HydrationEvent.Dehydrated -> """{"event":"dehydrated","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Failed     -> """{"event":"failed","path":${jsonEsc(e.path)},"error":${jsonEsc(e.error.message)}}"""
}

private fun jsonEsc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
