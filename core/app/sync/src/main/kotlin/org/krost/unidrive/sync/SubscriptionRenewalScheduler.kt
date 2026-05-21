package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * UD-303: Per-profile scheduled webhook-subscription renewer.
 *
 * Before this existed, [org.krost.unidrive.cli.SyncCommand.ensureSubscription]
 * was invoked every poll cycle. Behaviour was correct but chatty: on a short
 * poll interval (e.g. 30 s ACTIVE state) the daemon re-checked the `expires_at`
 * column hundreds of times per hour and hit the Graph renew endpoint the moment
 * the `<24h` window opened.
 *
 * This class fires exactly one renewal per `(lifetime − 24h)` window regardless
 * of sync cadence. Lifecycle is tied to the caller's scope — cancelling the
 * scope cancels the per-profile job (structured concurrency).
 *
 * The [renew] callback must be idempotent: on process restart we reschedule
 * from the persisted `expires_at`, and if the daemon crashed during the renewal
 * window we fall straight through to "renew now".
 *
 * Thread-safe: [schedule] / [isScheduled] / [cancel] are `@Synchronized` on the
 * jobs map.
 */
class SubscriptionRenewalScheduler(
    private val scope: CoroutineScope,
    private val store: SubscriptionStore,
    private val renew: suspend (profileName: String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val renewBeforeExpiry: java.time.Duration = java.time.Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(SubscriptionRenewalScheduler::class.java)
    private val jobs = mutableMapOf<String, Job>()

    /**
     * (Re)schedule renewal for [profileName] based on the store's current
     * `expires_at`. Cancels any prior job for the same profile so callers can
     * safely invoke this after each successful create/renew.
     *
     * If the store has no entry, or the entry already expired, this is a no-op —
     * the caller's per-cycle `ensureSubscription` will handle that case.
     */
    @Synchronized
    fun schedule(profileName: String) {
        jobs.remove(profileName)?.cancel()
        val sub = store.get(profileName) ?: return
        val now = Instant.now(clock)
        val fireAt = sub.expiresAt.minus(renewBeforeExpiry)
        val delayMs =
            java.time.Duration
                .between(now, fireAt)
                .toMillis()
                .coerceAtLeast(0L)
        val job =
            scope.launch {
                delay(delayMs)
                // Drop self from the jobs map BEFORE invoking the renew callback.
                // If we didn't, `isScheduledAndValid` called from inside `renew`
                // would still see the current (active) job plus the unrenewed
                // `expires_at > now + 24h` and wrongly short-circuit the renewal.
                removeSelf(profileName)
                try {
                    renew(profileName)
                } catch (e: Exception) {
                    log.warn("Scheduled webhook renewal failed for profile {}: {}", profileName, e.message)
                }
            }
        jobs[profileName] = job
        log.debug(
            "Scheduled webhook renewal for profile {} in {}ms (expires {}, fire {})",
            profileName,
            delayMs,
            sub.expiresAt,
            fireAt,
        )
    }

    /**
     * Short-circuit query for the per-cycle `ensureSubscription`: returns true
     * when a renewal job is live AND the persisted subscription still has more
     * than [renewBeforeExpiry] of life left. Callers who see `true` can skip
     * their own expiry arithmetic and Graph call entirely for this cycle.
     */
    @Synchronized
    fun isScheduledAndValid(profileName: String): Boolean {
        val job = jobs[profileName] ?: return false
        if (!job.isActive) return false
        val sub = store.get(profileName) ?: return false
        val now = Instant.now(clock)
        val remaining = java.time.Duration.between(now, sub.expiresAt)
        return remaining > renewBeforeExpiry
    }

    @Synchronized
    fun cancel(profileName: String) {
        jobs.remove(profileName)?.cancel()
    }

    /**
     * Remove the current entry for [profileName] from the jobs map without
     * cancelling the coroutine — used by the scheduled job itself after its
     * delay expires to clear the "scheduled" flag before invoking [renew].
     */
    @Synchronized
    private fun removeSelf(profileName: String) {
        jobs.remove(profileName)
    }

    @Synchronized
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
