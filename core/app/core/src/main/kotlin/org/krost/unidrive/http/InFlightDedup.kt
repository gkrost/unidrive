package org.krost.unidrive.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-205: in-flight request deduplication primitive, ported in spirit from
 * drive-desktop's `get-in-flight-request.ts`.
 *
 * When multiple concurrent callers ask for the same key, the first caller's
 * loader runs once and every later caller awaits the same `Deferred<V>`. Once
 * the deferred completes (success or failure), the entry is removed from the
 * map so the next call re-runs the loader. This is **not** a TTL cache — it
 * holds nothing across the request boundary; its only job is to fold concurrent
 * duplicate work into a single underlying operation.
 *
 * Design notes:
 *
 *  - **In-flight only.** No TTL, no LRU, no eviction policy. The map's residency
 *    is bounded by the lifetime of an outstanding loader call. After completion
 *    the entry is removed in the `finally` block.
 *  - **Failure propagation.** If the loader throws, the same exception is
 *    rethrown to every awaiting caller (the standard `Deferred.await()`
 *    contract). The map is cleared in `finally` regardless of outcome, so a
 *    subsequent `load(key, ...)` call re-invokes the loader — no negative
 *    caching.
 *  - **Lazy start.** The deferred is created with `CoroutineStart.LAZY` and
 *    only started when the caller that actually wins `putIfAbsent` awaits it.
 *    Losers discard their unstarted deferred without leaking a coroutine.
 *  - **Cancellation semantics.** Inherited from the surrounding
 *    `coroutineScope`: if any caller's scope is cancelled while awaiting, only
 *    that caller's `await()` raises `CancellationException`. The shared
 *    deferred itself is **not** cancelled by one caller cancelling, because the
 *    deferred is parented to the *current* `coroutineScope` of whichever caller
 *    happened to win `putIfAbsent`. Treat dedup'd loads as best-effort: don't
 *    rely on aggressive cancellation propagation across siblings.
 *  - **Key design.** Callers must construct stable, collision-free keys.
 *    Recommended pattern: `"<provider>:<operation>:<argument>"`, e.g.
 *    `"internxt:listChildren:/Documents"` or
 *    `"onedrive:getMetadata:01ABCDEF..."`. Two distinct semantic operations
 *    sharing the same key would silently swap return values.
 *  - **Type safety.** `K : Any` because `ConcurrentHashMap` rejects null keys;
 *    `V` may be nullable.
 *  - **Thread safety.** All map mutations go through `ConcurrentHashMap`'s
 *    atomic ops (`putIfAbsent`, `remove(key, value)`); no extra locking.
 *
 * Use sites in unidrive: read-heavy provider operations where many concurrent
 * sync-engine coroutines may legitimately ask for the same metadata
 * (folder listings, file info, valid credentials). Wrapping these calls in a
 * dedup collapses N concurrent HTTP round-trips into one without changing
 * call-site semantics — every caller still sees the same return value or the
 * same exception.
 *
 * Example:
 * ```
 * private val folderContentsDedup = InFlightDedup<String, FolderContentResponse>()
 *
 * suspend fun getFolderContents(uuid: String): FolderContentResponse =
 *     folderContentsDedup.load(uuid) { fetchFolderContentsFromServer(uuid) }
 * ```
 */
class InFlightDedup<K : Any, V> {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    /**
     * Run [loader] under [key], deduplicated against any concurrently
     * outstanding call for the same key. Returns the loader's result, or
     * rethrows whatever exception the loader threw.
     *
     * The map entry is cleared on completion (success or failure) so the
     * next call for the same key runs a fresh loader.
     */
    suspend fun load(
        key: K,
        loader: suspend () -> V,
    ): V =
        coroutineScope {
            // Fast path: an outstanding deferred already exists for this key.
            inFlight[key]?.let { return@coroutineScope it.await() }

            // Slow path: race to install our own deferred. Use LAZY so the
            // loser doesn't leak a started coroutine — only the winner ever
            // gets awaited.
            val deferred = async(start = CoroutineStart.LAZY) { loader() }
            val winner = inFlight.putIfAbsent(key, deferred) ?: deferred
            try {
                winner.await()
            } finally {
                // remove(key, value) is the conditional form: only removes
                // the entry if it still maps to *our* deferred. Protects
                // against the (theoretical) reorder where a later load() for
                // the same key has already installed a new deferred.
                inFlight.remove(key, winner)
            }
        }

    /** Number of outstanding (in-flight) keys. Exposed for tests / diagnostics. */
    internal fun inFlightCount(): Int = inFlight.size
}
