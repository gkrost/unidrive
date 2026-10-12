package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.krost.unidrive.CredentialHealth
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `credential_health` object of `daemon.status` (docs/dev/specs/unidrive-daemon-design.md §4.3): whether the
 * profile's credentials are still good, not only whether they were loaded (`authenticated`).
 *
 * The answer is the provider factory's own offline check ([org.krost.unidrive.ProviderFactory.checkCredentialHealth]:
 * token files and their expiry, no network call). It reads files and, for some providers, a credential store, so it is
 * never run inside a status request: [reading] returns the last result at once and, when that is missing or older than
 * [ttlMs], starts one single-flight re-check on [scope]. A status poll therefore never waits for it and never
 * repeats it more often than once per TTL.
 *
 * Unknown stays unknown: before the first check has finished, without a check at all ([check] null) and after a
 * check that threw, the state is [State.UNKNOWN]. It is never `ok` by default.
 */
internal class CredentialHealthMonitor(
    private val check: (() -> CredentialHealth)?,
    private val ttlMs: Long,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State(
        val wire: String,
    ) {
        /** The credentials are present and no expiry is known to be near. Says nothing about the server's view. */
        OK("ok"),

        /** The credential is valid but its provider reports an expiry that is near; `expires_in_ms` counts down. */
        EXPIRING("expiring"),

        /** The credential no longer works: its expiry passed, or the provider rejected it. A sign-in is needed. */
        EXPIRED("expired"),

        /** There is no usable credential on disk (never signed in, signed out, unreadable). */
        MISSING("missing"),

        /** The daemon does not know: no check has finished yet, none can run, the check failed, or it found something it cannot classify. */
        UNKNOWN("unknown"),
    }

    /** What one check found. [expiresAtMs] is absolute (epoch ms); null when the check knows no expiry. */
    data class Reading(
        val state: State,
        val expiresAtMs: Long?,
        val checkedAtMs: Long?,
    ) {
        /**
         * A rejection the engine saw ([authRejectedAtMs], see `MountEngine.authRejectedAtMs`) that is newer than the last
         * time the provider answered ([lastContactMs]) turns `ok`, `expiring` and `unknown` into `expired` at once, without
         * waiting for the next check: the provider refused the credentials after its own token refresh. `missing` stays
         * `missing` (more specific), and a rejection older than a success is history.
         */
        fun withRejection(
            authRejectedAtMs: Long?,
            lastContactMs: Long?,
        ): Reading {
            if (authRejectedAtMs == null) return this
            if (lastContactMs != null && authRejectedAtMs <= lastContactMs) return this
            return when (state) {
                State.OK, State.EXPIRING, State.UNKNOWN ->
                    Reading(State.EXPIRED, expiresAtMs = authRejectedAtMs, checkedAtMs = authRejectedAtMs)
                State.EXPIRED, State.MISSING -> this
            }
        }

        /** Time left at [nowMs], never negative; null when no expiry is known or the credential is not usable. */
        fun expiresInMs(nowMs: Long): Long? =
            when (state) {
                State.EXPIRED -> 0L
                State.EXPIRING -> expiresAtMs?.let { (it - nowMs).coerceAtLeast(0) }
                else -> null
            }
    }

    private val log = LoggerFactory.getLogger(CredentialHealthMonitor::class.java)

    @Volatile
    private var last: Reading? = null
    private val inFlight = AtomicBoolean(false)

    /** The last check's result, or an unknown one; starts a re-check when it is missing or older than the TTL. Never blocks. */
    fun reading(): Reading {
        val snapshot = last
        val checkedAt = snapshot?.checkedAtMs
        if (check != null && (checkedAt == null || clock() - checkedAt >= ttlMs)) refreshAsync()
        return snapshot ?: Reading(State.UNKNOWN, expiresAtMs = null, checkedAtMs = null)
    }

    /** Starts a check now unless one is running (the start-up call, so the first status already has an answer). */
    fun refreshAsync() {
        val run = check ?: return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch(ioDispatcher) {
            try {
                last = classify(run, clock)
            } finally {
                inFlight.set(false)
            }
        }
    }

    // The check is a provider factory's own code (file and credential-store reads): whatever it throws, the honest answer is
    // "unknown", never a crash of the status path.
    @Suppress("TooGenericExceptionCaught")
    private fun classify(
        run: () -> CredentialHealth,
        now: () -> Long,
    ): Reading {
        val startedAt = now()
        return try {
            val health = run()
            toReading(health, checkedAtMs = now())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("credential health check failed: {}", e.message)
            Reading(State.UNKNOWN, expiresAtMs = null, checkedAtMs = startedAt)
        }
    }

    companion object {
        /** Maps one offline check result to the wire state (see the spec table). [checkedAtMs] stamps the reading. */
        fun toReading(
            health: CredentialHealth,
            checkedAtMs: Long,
        ): Reading =
            when (health) {
                is CredentialHealth.Ok -> Reading(State.OK, expiresAtMs = null, checkedAtMs = checkedAtMs)
                is CredentialHealth.Missing -> Reading(State.MISSING, expiresAtMs = null, checkedAtMs = checkedAtMs)
                // A warning is a finding the factory could not put in a class (a token file that does not parse,
                // an access token past its life with no way to renew it): the daemon does not claim more than it knows.
                is CredentialHealth.Warning -> Reading(State.UNKNOWN, expiresAtMs = null, checkedAtMs = checkedAtMs)
                is CredentialHealth.ExpiresIn -> {
                    // remainingMs (exact) wins; whole hours are a lower bound of the time left, and 0 hours without it means expired.
                    val remainingMs = health.remainingMs ?: if (health.hours <= 0) 0L else health.hours * 3_600_000L
                    if (remainingMs <= 0) {
                        Reading(State.EXPIRED, expiresAtMs = checkedAtMs, checkedAtMs = checkedAtMs)
                    } else {
                        Reading(State.EXPIRING, expiresAtMs = checkedAtMs + remainingMs, checkedAtMs = checkedAtMs)
                    }
                }
            }
    }
}
