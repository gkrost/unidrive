package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.krost.unidrive.sync.PathNormalizer
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
 *   open_write  request:  {"verb":"hydration.open_write","handle_id":"...","path":"/foo","cache_path":"/home/.../foo.txt"[,"base_etag":"..."]}
 *   open_write  reply:    same as open_read; base_etag (OPTIONAL) is the etag the client
 *                         observed at list/open time — a mismatch refuses the write with
 *                         {"ok":false,"error":"conflict"} before any upload starts.
 *                         An excluded path (exclude_patterns match) answers
 *                         {"ok":true,"cache_path":"...","excluded":true} and emits a
 *                         `skipped` event instead of running an upload.
 *   close_handle request: {"verb":"hydration.close_handle","handle_id":"..."}
 *   close_handle reply:   {"ok":true}
 *   hydrate     request:  {"verb":"hydration.hydrate","path":"/foo"}
 *   hydrate     reply:    {"ok":true} or {"ok":false,"error":"<message>"}
 *   dehydrate   request:  {"verb":"hydration.dehydrate","path":"/foo"}
 *   dehydrate   reply:    {"ok":true} or {"ok":false,"error":"busy"} or {"ok":false,"error":"<message>"}
 *   mkdir       request:  {"verb":"hydration.mkdir","path":"/foo"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"outside_scope"}      path outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   unlink      request:  {"verb":"hydration.unlink","path":"/foo.txt"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"busy"}               the upload is in flight (#87)
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
 *                         (an excluded path adds ,"excluded":true — accepted, never uploaded)
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"path_exists"}        EEXIST
 *                         {"ok":false,"error":"outside_scope"}      path outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rename      request:  {"verb":"hydration.rename","old_path":"/a","new_path":"/b"[,"replace":true]}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"old_path_not_found"}    ENOENT
 *                         {"ok":false,"error":"new_parent_not_found"}  ENOENT
 *                         {"ok":false,"error":"new_path_exists"}       EEXIST
 *                         {"ok":false,"error":"outside_scope"}         either end outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}                 EIO
 *                         replace (OPTIONAL, default false) is POSIX
 *                         overwrite-if-exists for a FILE destination: the
 *                         existing destination is deleted first (same delete
 *                         path as unlink — trash/undo semantics), then the
 *                         source takes its place. With replace, an
 *                         open_write upload of the source or the destination
 *                         that is still queued or running refuses the rename
 *                         with {"ok":false,"error":"busy"} (nothing touched):
 *                         retry after the "completed" event for that handle.
 *
 *   open_write_begin request: {"verb":"hydration.open_write_begin","path":"/foo"}  [,"handle_id":"wh-N"]  reply ok: {"ok":true,"cache_path":"..."}  errs: unknown_path / path_is_folder / outside_scope
 *
 *   cancel      request:  {"verb":"hydration.cancel","path":"/foo"}
 *               reply:    {"ok":true,"cancelled":true}    a queued/running upload was aborted
 *                         {"ok":false,"error":"cancelled"} → the aborted upload's Completed carries this token
 *                         {"ok":true,"cancelled":false}    nothing in flight (idempotent)
 *                         Aborts every in-flight submission for the path (waiting, running,
 *                         or in retry backoff); the caller follows with the row-level verb
 *                         (unlink/rename) for the path itself.
 *
 *   Upload progress: while a client-written file uploads, the stream carries
 *   {"event":"uploading","path":"...","handle_id":"...","bytes_done":N,"bytes_total":M}
 *   coalesced to a few per second per file, correlated by the open_write
 *   handle like `completed`.
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

    // Connections that have issued any hydration verb and are still connected — i.e. the
    // FUSE co-daemon serving this profile's view. `handle()` is only ever called for
    // hydration verbs, and the refresh CLI uses sync.subscribe/refresh.run (NOT hydration
    // verbs), so this set never contains a transient CLI connection. Cleared on disconnect.
    private val mountConnections = ConcurrentHashMap.newKeySet<String>()

    /**
     * True when at least one connection has issued `hydration.subscribe`. The Phase-3 mount
     * signal once the co-daemon subscribes on mount; see mount-view-refresh-design.md §6.
     */
    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    /**
     * True when a FUSE co-daemon is currently serving this profile's view (any live connection
     * has issued a hydration verb). The daemon uses this to route `refresh.run` to the one-way
     * enumerate path rather than the legacy sync_root reconcile (mount-view-refresh-design.md §4).
     * Until the co-daemon subscribes on mount (Phase 3), this — not [hasSubscribers] — is the
     * working mount probe; it becomes true once the kernel first accesses the mount.
     */
    fun hasActiveMountConnection(): Boolean = mountConnections.isNotEmpty()

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
        // Called for EVERY connection close (not only subscribers), so it's also where a
        // mount-client connection drops out of the routing probe. Must run before the
        // not-a-subscriber early return.
        mountConnections.remove(connectionId)
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
            "hydration.cancel",
        )
    }
    suspend fun handle(connectionId: String, jsonRequest: String): String {
        val verb = pluck(jsonRequest, "verb") ?: return reply(ok = false, error = "missing_verb")
        // Any hydration verb on a connection marks it a live mount client (see mountConnections).
        mountConnections.add(connectionId)
        return when (verb) {
            "hydration.open_read" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.openForRead(connectionId, handleId, path)) {
                    is OpenResult.Ok -> openOkReply(r)
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                val cache = pluck(jsonRequest, "cache_path") ?: return reply(ok = false, error = "missing_cache_path")
                if (cache.isEmpty()) return reply(ok = false, error = "missing_cache_path")
                // base_etag is OPTIONAL: absent (or a row with no recorded token) →
                // unconditional upload, byte-identical to the pre-guard contract.
                val baseEtag = pluck(jsonRequest, "base_etag")
                when (val r = hydration.openForWrite(connectionId, handleId, path, Paths.get(cache), baseEtag)) {
                    is OpenResult.Ok -> openOkReply(r)
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write_begin" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                // handle_id is OPTIONAL: present → O_TRUNC live open (registers open-set entry);
                // absent → one-shot setattr/bare-truncate (no registration).
                val handleId = pluck(jsonRequest, "handle_id")
                when (val r = hydration.openWriteBegin(connectionId, path, handleId)) {
                    is OpenResult.Ok -> openOkReply(r)
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.close_handle" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                hydration.closeHandle(connectionId, handleId)
                reply(ok = true)
            }
            "hydration.hydrate" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.hydrate(path)) {
                    HydrateResult.Ok -> reply(ok = true)
                    is HydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.dehydrate" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.dehydrate(path)) {
                    DehydrateResult.Ok -> reply(ok = true)
                    DehydrateResult.Busy -> reply(ok = false, error = "busy")
                    is DehydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.last_synced" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
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
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.mkdir(path)) {
                    is MkdirResult.Ok -> reply(ok = true)
                    MkdirResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    is MkdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.unlink" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.unlink(path)) {
                    is UnlinkResult.Ok -> reply(ok = true)
                    UnlinkResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
                    UnlinkResult.Busy -> reply(ok = false, error = "busy")
                    is UnlinkResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rmdir" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.rmdir(path)) {
                    is RmdirResult.Ok -> reply(ok = true)
                    RmdirResult.PathIsFile -> reply(ok = false, error = "path_is_file")
                    RmdirResult.NotEmpty -> reply(ok = false, error = "not_empty")
                    is RmdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.create" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.create(connectionId, handleId, path)) {
                    is CreateResult.Ok ->
                        if (r.excluded) {
                            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"handle_id":${jsonEsc(r.handleId)},"excluded":true}"""
                        } else {
                            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"handle_id":${jsonEsc(r.handleId)}}"""
                        }
                    CreateResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    CreateResult.PathExists -> reply(ok = false, error = "path_exists")
                    is CreateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rename" -> {
                val oldPath = pluckPath(jsonRequest, "old_path") ?: return reply(ok = false, error = "missing_old_path")
                val newPath = pluckPath(jsonRequest, "new_path") ?: return reply(ok = false, error = "missing_new_path")
                // replace is OPTIONAL (default false): POSIX overwrite-if-exists.
                val replace = pluckBool(jsonRequest, "replace") ?: false
                when (val r = hydration.rename(oldPath, newPath, replace)) {
                    is RenameResult.Ok -> reply(ok = true)
                    RenameResult.OldPathNotFound -> reply(ok = false, error = "old_path_not_found")
                    RenameResult.NewParentNotFound -> reply(ok = false, error = "new_parent_not_found")
                    RenameResult.NewPathExists -> reply(ok = false, error = "new_path_exists")
                    is RenameResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.cancel" -> {
                val path = pluckPath(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                // Idempotent: cancelled=true when a queued/running upload was
                // aborted, false when nothing was in flight — both satisfy the
                // caller's "no upload happens" goal, so both are ok.
                val cancelled = hydration.cancelUpload(path)
                """{"ok":true,"cancelled":$cancelled}"""
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

    // `excluded` is only serialized when true — an additive wire field: a reply
    // on the common (non-excluded) path is byte-identical to the pre-field
    // contract, and existing corpus lines stay valid.
    private fun openOkReply(r: OpenResult.Ok): String =
        if (r.excluded) {
            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"excluded":true}"""
        } else {
            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
        }

    // Plucks a LOGICAL co-daemon path field and canonicalises it to NFC. This is the
    // single point where co-daemon paths enter the JVM; normalizing once here makes
    // every downstream op (cache resolution + provider delete/createFolder/move + the
    // StateDatabase NFC lookups) see the same NFC form as the NFC-stored data. The
    // co-daemon may send a decomposed (NFD) name (macOS-origin accents) for a row
    // stored composed (NFC) — without this an NFD deleteRemote 404s on the NFC cloud
    // object and an NFD cache lookup misses the NFC-named file. Mirrors the existing
    // ingestion-chokepoint approach. Runs on the JSON-DECODED value, so an escaped
    // decomposed form (o + escaped combining diaeresis) is normalized too. NOT applied to
    // `cache_path` (a literal local filesystem path the co-daemon already created, used
    // verbatim) or `prefix` (StateDatabase.listDirectChildren already normalizes it).
    private fun pluckPath(line: String, key: String): String? =
        pluck(line, key)?.let { PathNormalizer.nfc(it) }

    // Minimal JSON pluck — returns the decoded value of a TOP-LEVEL string member of the
    // request object. Sufficient for our verb messages; we don't accept arbitrary client
    // JSON shapes. Walks the object's members (skipping over any non-matching value, nested
    // objects/arrays included) rather than searching for the first `"key"` substring, so a
    // value or nested member that merely spells the key is never mistaken for it. A missing
    // key, a non-string value (incl. null) or malformed text yields null.
    private fun pluck(line: String, key: String): String? {
        var i = skipWs(line, 0)
        if (i >= line.length || line[i] != '{') return null
        i = skipWs(line, i + 1)
        while (i < line.length && line[i] == '"') {
            val (name, afterName) = readString(line, i) ?: return null
            i = skipWs(line, afterName)
            if (i >= line.length || line[i] != ':') return null
            i = skipWs(line, i + 1)
            if (i >= line.length) return null
            if (name == key) return if (line[i] == '"') readString(line, i)?.first else null
            val afterValue = skipValue(line, i)
            if (afterValue < 0) return null
            i = skipWs(line, afterValue)
            if (i < line.length && line[i] == ',') i = skipWs(line, i + 1) else break
        }
        return null
    }

    // Boolean pluck for OPTIONAL verb flags (e.g. rename's `replace`). Accepts the
    // bare JSON literals true/false — what System.Text.Json and serde_json emit —
    // and, defensively, the quoted strings "true"/"false". A missing key, a bare
    // null, or any other value yields null so the caller applies its default.
    // Walks the object's members with the same structure as [pluck].
    private fun pluckBool(line: String, key: String): Boolean? {
        var i = skipWs(line, 0)
        if (i >= line.length || line[i] != '{') return null
        i = skipWs(line, i + 1)
        while (i < line.length && line[i] == '"') {
            val (name, afterName) = readString(line, i) ?: return null
            i = skipWs(line, afterName)
            if (i >= line.length || line[i] != ':') return null
            i = skipWs(line, i + 1)
            if (i >= line.length) return null
            if (name == key) {
                return when {
                    line.startsWith("true", i) -> true
                    line.startsWith("false", i) -> false
                    line[i] == '"' -> readString(line, i)?.first?.let {
                        when (it.lowercase()) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        }
                    }
                    else -> null
                }
            }
            val afterValue = skipValue(line, i)
            if (afterValue < 0) return null
            i = skipWs(line, afterValue)
            if (i < line.length && line[i] == ',') i = skipWs(line, i + 1) else break
        }
        return null
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    // Decodes the JSON string literal whose opening quote is at [start]; returns the text and
    // the index just past the closing quote. Handles the full escape set — quote, backslash,
    // slash, b, f, n, r, t and the four-hex-digit unicode escape (a surrogate pair is two
    // consecutive escapes; appending each UTF-16 unit in turn reassembles it). .NET's
    // System.Text.Json writes every non-ASCII char in that unicode form by default. A malformed
    // escape returns null rather than a guess: silently keeping the digits is what made
    // "K" + o-umlaut + "ln" arrive as "Ku00f6ln" (#416).
    private fun readString(s: String, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return sb.toString() to i + 1
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= s.length) return null
            when (val e = s[i + 1]) {
                '"', '\\', '/' -> sb.append(e)
                'b' -> sb.append('\b')
                'f' -> sb.append(0x0C.toChar())
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 6 > s.length) return null
                    var unit = 0
                    for (d in i + 2 until i + 6) {
                        val h = Character.digit(s[d], 16)
                        if (h < 0 || s[d].code > 0x7F) return null
                        unit = unit * 16 + h
                    }
                    sb.append(unit.toChar())
                    i += 4
                }
                else -> return null
            }
            i += 2
        }
        return null
    }

    // Index just past the JSON value starting at [start] (string, object/array, or bare
    // literal such as a number/true/null), or -1 if malformed. Strings are skipped whole so
    // brackets and commas inside them are never counted.
    private fun skipValue(s: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '"' -> {
                    i = readString(s, i)?.second ?: return -1
                    if (depth == 0) return i
                    continue
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    if (depth == 0) return i
                    depth--
                    if (depth == 0) return i + 1
                }
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return if (depth == 0) i else -1
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
            .append(",\"remote_modified_ms\":").append(e.remoteModifiedEpochMillis?.toString() ?: "null")
            .append(",\"remote_id\":").append(e.remoteId?.let { jsonEsc(it) } ?: "null")
            .append(",\"etag\":").append(e.etag?.let { jsonEsc(it) } ?: "null")
            .append(",\"pending_upload\":").append(e.pendingUpload)
            .append(",\"error\":").append(e.hasError)
        if (e.excluded) sb.append(",\"excluded\":true")
        sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
}

fun serialiseHydrationEvent(e: HydrationEvent): String = when (e) {
    is HydrationEvent.Hydrating  -> """{"event":"hydrating","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Hydrated   -> """{"event":"hydrated","path":${jsonEsc(e.path)},"bytes":${e.bytes}}"""
    is HydrationEvent.Dehydrated -> """{"event":"dehydrated","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Skipped    -> """{"event":"skipped","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Queued     -> """{"event":"queued","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Uploading -> {
        """{"event":"uploading","path":${jsonEsc(e.path)},"handle_id":${jsonEsc(e.handleId)},""" +
            """"bytes_done":${e.bytesDone},"bytes_total":${e.bytesTotal}}"""
    }
    is HydrationEvent.Failed -> {
        val base = """{"event":"failed","path":${jsonEsc(e.path)},"error":${jsonEsc(e.error.message)}"""
        // retry_scheduled is only serialized for upload attempts; download and
        // verb failures keep the pre-existing shape byte-identical.
        if (e.retryScheduled == null) "$base}" else "$base,\"retry_scheduled\":${e.retryScheduled}}"
    }
    is HydrationEvent.Completed -> {
        val direction = if (e.direction == HydrationEvent.Completed.Direction.UPLOAD) "upload" else "download"
        val base = """{"event":"completed","path":${jsonEsc(e.path)},"handle_id":${jsonEsc(e.handleId)},"direction":"$direction","ok":${e.ok}"""
        if (e.ok) "$base}" else "$base,\"error\":${jsonEsc(e.error?.message ?: "unknown")}}"
    }
    is HydrationEvent.ViewInvalidated -> {
        if (e.full) {
            """{"event":"view.invalidated","full":true}"""
        } else {
            val paths = e.paths.joinToString(",") { jsonEsc(it) }
            """{"event":"view.invalidated","paths":[$paths]}"""
        }
    }
}

// JSON string literal for [s]. Quote and backslash AND control characters are escaped: a
// provider's error message (an HTTP error body, say) routinely carries a newline, and one raw
// newline in a reply or event line splits the NDJSON framing for the reader.
private fun jsonEsc(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '\\' -> sb.append('\\').append('\\')
            c == '"' -> sb.append('\\').append('"')
            c == '\n' -> sb.append('\\').append('n')
            c == '\r' -> sb.append('\\').append('r')
            c == '\t' -> sb.append('\\').append('t')
            c < ' ' -> sb.append('\\').append('u').append("%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}
