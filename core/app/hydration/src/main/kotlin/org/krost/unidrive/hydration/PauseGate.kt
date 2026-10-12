package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * #778: the daemon's pause. While it is set, no upload starts, no cloud download starts and the poll's
 * cloud enumeration waits; local writes are still queued. The state is held in memory only: a restarted
 * daemon runs, and the client re-applies its rules.
 *
 * One gate per mount front-end ([MountEngine.pause]); the transfers consult it, `daemon.pause` and
 * `daemon.resume` set it. It also tracks the transfers in flight ([track]) for the rule applied when a
 * pause begins ([abortSlowTransfers]): a transfer whose estimated time remaining is under
 * [FINISH_UNDER_MS] is left to finish, any other is aborted and its owner re-queues it.
 */
class PauseGate(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** What `daemon.status` reports as `paused`: [sinceMs] is epoch ms, [auto] is whether a rule (not a person) paused. */
    data class State(
        val reason: String,
        val sinceMs: Long,
        val auto: Boolean,
    )

    /** What a [pause] call changed. */
    enum class Change {
        /** The daemon was running and is paused now. */
        STARTED,

        /** It was paused; the reason or `auto` is now different. The pause began at the first request. */
        UPDATED,

        /** It was paused with exactly this state. */
        UNCHANGED,
    }

    private val state = MutableStateFlow<State?>(null)

    val current: State? get() = state.value

    val isPaused: Boolean get() = state.value != null

    /**
     * Pauses the daemon. Pausing while paused keeps the first [State.sinceMs] (the pause began then) and takes
     * the new [reason] and [auto]; the returned [Change] says whether anything differed.
     */
    @Synchronized
    fun pause(
        reason: String,
        auto: Boolean,
    ): Change {
        val previous = state.value
        val next = State(reason, previous?.sinceMs ?: clockMs(), auto)
        state.value = next
        return when {
            previous == null -> Change.STARTED
            previous == next -> Change.UNCHANGED
            else -> Change.UPDATED
        }
    }

    /** Ends the pause. True when the daemon was paused, false (a no-op) when it was running. */
    @Synchronized
    fun resume(): Boolean {
        val wasPaused = state.value != null
        state.value = null
        return wasPaused
    }

    /** Returns at once while running; while paused, suspends until [resume]. */
    suspend fun awaitResumed() {
        state.first { it == null }
    }

    // ── transfers in flight ─────────────────────────────────────────────────────────────────────

    /**
     * One transfer between its start and its end. The owner reports its progress ([report], or [measuredBy]
     * for a transfer whose bytes are on disk); [abortSlowTransfers] reads it.
     */
    class Transfer internal constructor(
        val path: String,
        private val nanoTime: () -> Long,
    ) {
        @Volatile private var startedNanos = nanoTime()

        @Volatile private var done = 0L

        @Volatile private var total = 0L

        @Volatile private var probe: (() -> Long)? = null

        @Volatile internal var job: Job? = null

        @Volatile internal var abortedForPause = false

        /** The bytes start to move now (a permit was granted); the rate is measured from here. */
        fun begin() {
            startedNanos = nanoTime()
        }

        /** The provider's own progress: [done] of [total] bytes. */
        fun report(
            done: Long,
            total: Long,
        ) {
            this.total = total
            this.done = done
        }

        /** Progress read from the bytes on disk (a download's staging file): [total] expected, [bytesDone] asked when needed. */
        fun measuredBy(
            total: Long,
            bytesDone: () -> Long,
        ) {
            this.total = total
            this.probe = bytesDone
        }

        /**
         * Estimated time remaining in ms at the rate so far, or null when it cannot be told (nothing moved yet,
         * or the size is unknown).
         */
        fun etaMs(): Long? {
            val expected = total
            val moved = probe?.invoke() ?: done
            if (expected <= 0 || moved <= 0) return null
            if (moved >= expected) return 0
            val elapsedNs = nanoTime() - startedNanos
            if (elapsedNs <= 0) return null
            return ((expected - moved).toDouble() * elapsedNs / moved / NANOS_PER_MS).toLong()
        }
    }

    private val transfers: MutableSet<Transfer> = ConcurrentHashMap.newKeySet()

    /** Transfers between [track]'s start and end right now. */
    fun inFlightCount(): Int = transfers.size

    /**
     * Runs [block] as a transfer in flight. Throws [TransferPausedException] without running it when the daemon
     * is paused, and also when a pause that began meanwhile aborted it ([abortSlowTransfers]); the owner
     * re-queues. A failure of [block] and a cancellation that did not come from a pause pass through unchanged.
     */
    suspend fun <T> track(
        path: String,
        block: suspend (Transfer) -> T,
    ): T {
        val transfer = Transfer(path, nanoTime)
        // Registered before the check: a pause that lands after the check finds the transfer in
        // abortSlowTransfers, one that landed before is seen by the check.
        transfers += transfer
        try {
            if (isPaused) throw TransferPausedException()
            return coroutineScope { runAbortable(transfer, block) }
        } finally {
            transfers -= transfer
        }
    }

    private suspend fun <T> CoroutineScope.runAbortable(
        transfer: Transfer,
        block: suspend (Transfer) -> T,
    ): T {
        // Undispatched: the block runs at once, as it did before it was tracked, so a caller's own scheduling
        // (and the test dispatchers that count on it) sees no extra hop on the way to the transfer.
        val work = async(start = CoroutineStart.UNDISPATCHED) { block(transfer) }
        transfer.job = work
        if (transfer.abortedForPause) work.cancel(PauseCancellation())
        try {
            return work.await()
        } catch (e: CancellationException) {
            if (transfer.abortedForPause && currentCoroutineContext().isActive) throw TransferPausedException()
            throw e
        }
    }

    /**
     * The rule for a pause that has just begun: every transfer in flight whose estimated time remaining is under
     * [finishUnderMs] is left to finish, any other (no estimate included) is aborted; its [track] throws
     * [TransferPausedException]. Returns how many were aborted.
     */
    fun abortSlowTransfers(finishUnderMs: Long = FINISH_UNDER_MS): Int {
        var aborted = 0
        for (transfer in transfers) {
            val eta = transfer.etaMs()
            if (eta != null && eta < finishUnderMs) continue
            transfer.abortedForPause = true
            transfer.job?.cancel(PauseCancellation())
            aborted++
        }
        return aborted
    }

    private class PauseCancellation : CancellationException("paused")

    companion object {
        /** A transfer that needs less than this much longer is left to finish when a pause begins. */
        const val FINISH_UNDER_MS: Long = 10_000

        private const val NANOS_PER_MS = 1_000_000.0
    }
}

/**
 * A transfer was stopped, or refused, because the daemon is paused. Not a failure: the owner leaves the work
 * queued (an upload) or answers `paused` (a download). No stack trace: it is control flow.
 */
class TransferPausedException : RuntimeException("paused", null, false, false)
