package org.krost.unidrive.hydration

import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What the daemon remembers of the cloud objects it found truncated: the stored object is shorter than the size the
 * drive reports, so every download is refused with `remote_incomplete`. Without a memory each read or pin of such an
 * object asks the provider again (metadata calls and a GET answered with its headers) and logs again.
 *
 * Keyed by remote id, in memory only: a restart forgets, and the first read afterwards asks once more. An entry
 * answers repeats only while the row still describes the same cloud version it was recorded against (size, hash and
 * modification time, so a re-upload or a repaired object is noticed at the next read) and for [ttlMs]; after that
 * one real attempt revalidates it (a success removes it, a refusal records it afresh).
 */
internal class RemoteIncompleteMemory(
    @Volatile var ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Version(
        val size: Long,
        val hash: String?,
        val modified: Instant?,
    )

    private data class Seen(
        val storedBytes: Long,
        val declaredBytes: Long,
        val observedAtMs: Long,
        val version: Version,
    )

    private val seen = ConcurrentHashMap<String, Seen>()

    // Never cleared: the WARN of an object is logged once per daemon run.
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** How many objects are remembered as truncated (what `daemon.status.remote_incomplete.count` reports). */
    val count: Int get() = seen.size

    /**
     * The refusal to answer a read of [entry] with, or null when the provider has to be asked: nothing remembered,
     * the cloud version differs from the remembered one (the entry is dropped), or the entry is older than [ttlMs]
     * (kept until the attempt it makes ends).
     */
    fun recall(entry: SyncEntry): RemoteIncompleteDownloadException? {
        val id = idOf(entry) ?: return null
        val s = seen[id] ?: return null
        if (s.version != versionOf(entry)) {
            seen.remove(id, s)
            return null
        }
        if (clock() - s.observedAtMs >= ttlMs) return null
        return RemoteIncompleteDownloadException(
            "Stored object is short: ${s.storedBytes} of ${s.declaredBytes} bytes (remembered, the provider was not asked again)",
            storedBytes = s.storedBytes,
            declaredBytes = s.declaredBytes,
        )
    }

    /** Records the provider's refusal of [entry]; true the first time in this run for this object (the WARN is due). */
    fun remember(
        entry: SyncEntry,
        refusal: RemoteIncompleteDownloadException,
    ): Boolean {
        val id = idOf(entry) ?: return false
        seen[id] = Seen(refusal.storedBytes, refusal.declaredBytes, clock(), versionOf(entry))
        return warned.add(id)
    }

    /** The object downloaded whole: it is not truncated (any more). */
    fun forget(entry: SyncEntry) {
        idOf(entry)?.let { seen.remove(it) }
    }

    private fun idOf(entry: SyncEntry): String? = entry.remoteId?.takeIf { it.isNotEmpty() }

    private fun versionOf(entry: SyncEntry) = Version(entry.remoteSize, entry.remoteHash, entry.remoteModified)

    companion object {
        /** One hour: after it the next read revalidates with one real attempt. */
        const val DEFAULT_TTL_MS: Long = 60L * 60 * 1000
    }
}
