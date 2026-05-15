package org.krost.unidrive.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * UD-338: shared serialisation + cancellation-survival pattern for
 * provider token-refresh flows.
 *
 * Lifted from the two near-identical loops in
 * `OneDriveProvider.TokenManager.getValidToken` (UD-310 + UD-111) and
 * `InternxtProvider.AuthService.refreshToken` (UD-331). Both used the
 * same skeleton:
 *
 *   refreshMutex.withLock {
 *       // re-read the current value under the lock
 *       // double-check: did a previous caller already refresh while we waited?
 *       if (work-already-done-by-prior-caller) return current
 *
 *       withContext(NonCancellable) {
 *           // network call + persist
 *           // wrapped so a cancellation between "got new token" and
 *           // "wrote it to disk" can't leave in-memory + on-disk
 *           // states disagreeing.
 *       }
 *   }
 *
 * Per-provider variations stay with the provider:
 *  - The stale predicate (`isNearExpiry()` for OneDrive,
 *    `jwt != stale.jwt` re-check for Internxt) lives in the
 *    `isAlreadyFresh` lambda.
 *  - Force-refresh, failure-recording, and persistence-error handling
 *    stay in the caller's `body`. This helper does not try to
 *    generalise UD-310 / UD-111 — those are intentional provider
 *    features, not duplication.
 *
 * Why a class rather than a `suspend fun` extension on `Mutex`:
 * encapsulating the mutex behind a named latch makes "this provider
 * has exactly one refresh loop" structurally obvious to a reader, and
 * lets future maintainers add per-latch observability (e.g. wait-time
 * histograms for UD-111 telemetry) without changing every call site.
 */
class RefreshableTokenLatch {
    private val mutex = Mutex()

    /**
     * Serialise concurrent token-refresh attempts and survive cancellation
     * during the network-call-plus-persist phase.
     *
     * @param isAlreadyFresh predicate called once we hold the lock; if it
     *   returns a non-null `T`, that value is returned and [body] is
     *   skipped. Use this to short-circuit when another coroutine refreshed
     *   the token while this one was queued at the mutex. Returning null
     *   means "still stale, please refresh."
     * @param body the network call + persist. Runs inside
     *   [kotlinx.coroutines.NonCancellable] so a parent-scope cancellation
     *   can't tear it in half between "got new token" and "wrote it to
     *   disk."
     */
    suspend fun <T : Any> withRefresh(
        isAlreadyFresh: () -> T?,
        body: suspend () -> T,
    ): T =
        mutex.withLock {
            isAlreadyFresh()?.let { return@withLock it }
            withContext(NonCancellable) { body() }
        }
}
