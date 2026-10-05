package org.krost.unidrive.engine

import org.krost.unidrive.ProviderException

/**
 * How a failed provider call is classified as "the remote item does not exist".
 * Shared by the mirror engine (a planned DeleteRemote, the #419 ghost probe)
 * and the mount operations (deleteRemote, remoteItemOrNull, the rescan's
 * remote-version check). Moved out of SyncEngine for #560 U2; the answer is
 * unchanged.
 *
 * The reflective `getStatusCode()` probe stays with each caller (SyncEngine,
 * HydrationImpl): Method.invoke checks access from the CALLER's package, so a
 * probe here could not read the package-private exceptions their tests throw.
 */
object RemoteErrors {
    /**
     * Returns true if and only if [e] is a typed provider signal that the
     * remote path is already gone — making a delete idempotent on that
     * outcome. Two specific shapes qualify:
     *
     * 1. **Path-resolution failure** — InternxtProvider.resolveFolder walks
     *    the path tree and throws `ProviderException("Folder not found: <seg>
     *    in <path>")` when a parent folder no longer exists on the remote. This
     *    was the shape observed in the live bug.
     *
     * 2. **Direct metadata miss** — InternxtProvider.getMetadata throws
     *    `ProviderException("Item not found: <path>")` when the target itself
     *    is absent (parent exists, but the leaf is gone).
     *
     * OneDrive's provider handles HTTP 404 internally and never propagates it
     * here — OneDriveProvider.delete returns normally when Graph returns 404.
     *
     * Anything that is NOT a [ProviderException] (e.g. a bare RuntimeException,
     * IOException) returns false and will be re-thrown. A [ProviderException]
     * with a different message prefix (e.g. a 5xx body that happens to contain
     * "not found" or "404") also returns false — the anchored prefix match
     * closes the free-text misclassification hole.
     */
    fun isAlreadyGone(e: Throwable): Boolean {
        if (e !is ProviderException) return false
        val msg = e.message ?: return false
        return msg.startsWith("Folder not found: ") || msg.startsWith("Item not found: ")
    }
}
