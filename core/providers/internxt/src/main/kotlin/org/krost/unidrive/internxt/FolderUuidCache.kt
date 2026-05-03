package org.krost.unidrive.internxt

import java.util.concurrent.ConcurrentHashMap

/**
 * UD-357: process-local cache of `(parentUuid, sanitizedName) -> uuid` for
 * Internxt folder lookups.
 *
 * Internxt's API has a 1-3 second read-after-write inconsistency window
 * between `POST /drive/folders` and `GET /drive/folders/{parentUuid}`:
 * the create returns 200 with the new folder's UUID, but the next listing
 * call doesn't yet show that child. Multi-level mkdir cascades fail with
 * `Folder not found: X in /X` because step 2's `resolveFolder` walks the
 * path-segment listings looking for the just-created step-1 folder and
 * doesn't find it.
 *
 * This cache short-circuits the listing call for paths the provider itself
 * just created. It's populated:
 *   - on `createFolder` success, with the API-returned UUID, so the next
 *     `resolveFolder` immediately following bypasses the inconsistent
 *     listing endpoint;
 *   - on a successful list-and-find inside `resolveFolder`, so subsequent
 *     resolutions of the same parent skip the round-trip — a free latency
 *     win on top of the bug fix.
 *
 * Thread-safe: backed by `ConcurrentHashMap`. The provider is a singleton
 * per profile; multiple coroutines can hit the cache concurrently from
 * Pass-2 upload workers.
 *
 * No eviction: cache lives for the process lifetime. Internxt-folder
 * tree size on a typical drive is bounded (folder counts in the
 * thousands, each entry ≈ 100 bytes — ~MB-scale at worst). A folder
 * created via this cache then deleted out-of-band (web UI, another sync
 * session) will produce a stale entry whose UUID returns 404 on the next
 * operation. Acceptable trade for the in-process scope; a follow-up
 * could invalidate on observed 404 if it bites in practice.
 */
class FolderUuidCache {
    private val map = ConcurrentHashMap<Key, String>()

    fun put(
        parentUuid: String,
        sanitizedName: String,
        uuid: String,
    ) {
        map[Key(parentUuid, sanitizedName)] = uuid
    }

    fun get(
        parentUuid: String,
        sanitizedName: String,
    ): String? = map[Key(parentUuid, sanitizedName)]

    /** For tests. Returns the number of cached entries. */
    fun size(): Int = map.size

    /** For tests / future eviction. Drops every entry. */
    fun clear() = map.clear()

    private data class Key(
        val parentUuid: String,
        val sanitizedName: String,
    )
}
