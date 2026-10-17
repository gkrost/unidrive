package org.krost.unidrive.telemetry

import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The request-attempt metrics of one profile: attempts handed in by the providers' HTTP clients
 * ([RequestMetricsSink]), written to the attempt tables of the profile's `telemetry.db` by one background
 * thread behind a bounded queue, rolled up per minute and per endpoint class, and read back by [query].
 *
 * - **A request never waits for the disk.** [record] offers the attempt to the queue and returns; a full queue
 *   drops it and counts it. A failed write drops that batch and counts it; the writer carries on.
 * - **Roll-ups.** The writer recomputes the minutes that received attempts every
 *   [RequestMetricsConfig.rollupIntervalMs], and [query] asks for a pass first, so the current minute is included
 *   (it is still open: its roll-up grows until the minute has passed). After a crash the minutes left unrolled
 *   are rolled up at the next open.
 * - **Retention.** Raw rows after [RequestMetricsConfig.rawRetentionHours], roll-ups after
 *   [RequestMetricsConfig.rollupRetentionDays]. The file is shared with the transfer history and bounded by the
 *   history's byte budget; when it is over, raw attempts go first, then roll-ups, and only then transfer rows.
 *
 * Obtained from [TransferHistory.requestMetrics]; it uses that history's file and is closed with it.
 */
@Suppress("TooManyFunctions") // one writer thread: its control ops, write, roll-up and retention steps stay together
class RequestMetricsRecorder internal constructor(
    private val profile: String,
    private val config: RequestMetricsConfig,
    private val maxBytes: Long,
    private val clock: () -> Long,
    private val store: TelemetryStore,
) : RequestMetricsSink,
    AutoCloseable {
    private sealed interface Op {
        class Write(
            val attempt: RequestAttempt,
        ) : Op

        class Barrier(
            val done: CompletableFuture<Unit>,
        ) : Op

        class Prune(
            val done: CompletableFuture<Unit>,
        ) : Op

        data object Stop : Op
    }

    private val queue = ArrayBlockingQueue<Op>(config.queueCapacity)
    private val written = AtomicLong()
    private val dropped = AtomicLong()
    private val droppedUnrecorded = AtomicBoolean()
    private val accepting = AtomicBoolean(true)
    private val thread = Thread(::writerLoop, "request-metrics-$profile").apply { isDaemon = true }

    // Writer thread only.
    private val dirtyMinutes = LinkedHashSet<Long>()
    private var lastRollupNanos = 0L
    private var lastPruneNanos = 0L
    private var lastWarnNanos = 0L

    val stats: RequestMetricsStats
        get() = RequestMetricsStats(written.get(), dropped.get(), dropped.get() > 0, queue.size)

    internal fun start() = thread.start()

    /** Hot path: hands the attempt to the queue; a full queue drops it and counts it. Never blocks, never throws. */
    override fun record(attempt: RequestAttempt) {
        if (!accepting.get()) return
        if (!queue.offer(Op.Write(attempt))) {
            dropped.incrementAndGet()
            droppedUnrecorded.set(true)
        }
    }

    /** Waits until everything queued so far is written and the touched minutes are rolled up. False on timeout or when closed. */
    fun flush(timeoutMs: Long = DEFAULT_WAIT_MS): Boolean = control(timeoutMs) { Op.Barrier(it) }

    /** Applies the retention now, on the writer thread, and waits for it. */
    fun pruneNow(): Boolean = control(DEFAULT_WAIT_MS) { Op.Prune(it) }

    private fun control(
        timeoutMs: Long,
        op: (CompletableFuture<Unit>) -> Op,
    ): Boolean {
        if (!thread.isAlive) return false
        val done = CompletableFuture<Unit>()
        return try {
            if (!queue.offer(op(done), timeoutMs, TimeUnit.MILLISECONDS)) return false
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            log.warn("Request metrics for {} did not answer within {} ms: {}", profile, timeoutMs, e.javaClass.simpleName)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * The roll-ups in [q], after a flush (a flush that times out is not an error: what is written is read). An
     * empty result is an empty series: no attempt was recorded in the range, which is not the same as zero requests.
     * Throws [SQLException] when the file cannot be read.
     */
    fun query(
        q: RollupQuery,
        waitMs: Long = QUERY_WAIT_MS,
    ): RollupResult {
        flush(waitMs)
        return store.readRollups(q)
    }

    /** When attempts were first lost from this file (epoch milliseconds), or null when none is known to be. */
    fun incompleteSinceMs(): Long? = store.meta("metrics_incomplete_since")?.toLongOrNull()

    /** Test seam: the process dies; nothing more is written or rolled up. */
    internal fun abandon() {
        accepting.set(false)
        thread.interrupt()
        thread.join(JOIN_TIMEOUT_MS)
    }

    /** Writes what is queued, rolls up, stops the thread. Safe to call twice. */
    override fun close() {
        if (!accepting.getAndSet(false)) return
        if (thread.isAlive) {
            queue.offer(Op.Stop, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            thread.join(JOIN_TIMEOUT_MS)
        }
    }

    private fun writerLoop() {
        val batch = ArrayList<Op>(config.batchSize)
        try {
            catchUp()
            prune()
            var running = true
            while (running) {
                val first = queue.poll(idleWaitMs(nextDueInNs()), TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, config.batchSize - 1)
                }
                running = process(batch)
                batch.clear()
                if (running) {
                    rollUpIfDue()
                    pruneIfDue()
                }
            }
            rollUp()
        } catch (e: InterruptedException) {
            log.debug("Request metrics writer for {} interrupted: {}", profile, e.message)
        }
    }

    private fun process(batch: List<Op>): Boolean {
        val pending = ArrayList<RequestAttempt>()
        var keepRunning = true
        for (op in batch) {
            when (op) {
                is Op.Write -> pending += op.attempt
                is Op.Barrier -> {
                    write(pending)
                    rollUp()
                    op.done.complete(Unit)
                }
                is Op.Prune -> {
                    write(pending)
                    rollUp()
                    prune()
                    op.done.complete(Unit)
                }
                Op.Stop -> keepRunning = false
            }
        }
        write(pending)
        return keepRunning
    }

    @Suppress("TooGenericExceptionCaught") // metrics are never a correctness dependency: nothing here may kill the writer
    private fun write(pending: MutableList<RequestAttempt>) {
        if (pending.isEmpty()) return
        val rows = pending.toList()
        pending.clear()
        try {
            store.insertAttempts(profile, rows)
            written.addAndGet(rows.size.toLong())
            rows.forEach { dirtyMinutes += minuteOf(it.startedAtMs) }
            recordGapIfAny()
        } catch (e: SQLException) {
            writeFailed(rows.size, e)
        } catch (e: RuntimeException) {
            writeFailed(rows.size, e)
        }
    }

    private fun writeFailed(
        rows: Int,
        e: Exception,
    ) {
        dropped.addAndGet(rows.toLong())
        droppedUnrecorded.set(true)
        val now = System.nanoTime()
        if (lastWarnNanos == 0L || now - lastWarnNanos > WARN_EVERY_NANOS) {
            lastWarnNanos = now
            log.warn(
                "Request metrics for {}: {} attempt(s) dropped, requests are not affected ({}: {}); {} dropped so far",
                profile,
                rows,
                e.javaClass.simpleName,
                HistorySanitizer.message(e.message),
                dropped.get(),
            )
        }
    }

    /** After a write succeeded: leave a mark in the file that attempts are missing from it. */
    private fun recordGapIfAny() {
        if (!droppedUnrecorded.getAndSet(false)) return
        try {
            store.putMeta("metrics_incomplete_since", clock().toString(), onlyIfAbsent = true)
            store.putMeta("metrics_dropped_attempts", dropped.get().toString())
        } catch (e: SQLException) {
            droppedUnrecorded.set(true)
            log.debug("Request metrics for {}: cannot record the gap yet: {}", profile, e.message)
        }
    }

    private fun catchUp() {
        try {
            dirtyMinutes += store.unrolledMinutes()
            rollUp()
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: roll-up of the earlier run failed ({})", profile, HistorySanitizer.message(e.message))
        }
    }

    private fun rollUpIfDue() {
        if (dirtyMinutes.isEmpty()) return
        if (rollupDueInNs() <= 0) rollUp()
    }

    private fun rollupDueInNs(): Long = TimeUnit.MILLISECONDS.toNanos(config.rollupIntervalMs) - (System.nanoTime() - lastRollupNanos)

    private fun pruneDueInNs(): Long = TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs) - (System.nanoTime() - lastPruneNanos)

    // What an idle writer waits for: the retention, and the roll-up while minutes are waiting for one.
    private fun nextDueInNs(): Long = if (dirtyMinutes.isEmpty()) pruneDueInNs() else minOf(pruneDueInNs(), rollupDueInNs())

    @Suppress("TooGenericExceptionCaught")
    private fun rollUp() {
        lastRollupNanos = System.nanoTime()
        if (dirtyMinutes.isEmpty()) return
        val minutes = dirtyMinutes.toList()
        try {
            store.rollUp(minutes)
            dirtyMinutes.clear()
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: roll-up failed, retrying ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("Request metrics for {}: roll-up failed, retrying ({})", profile, e.javaClass.simpleName)
        }
    }

    private fun pruneIfDue() {
        if (pruneDueInNs() <= 0) prune()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun prune() {
        lastPruneNanos = System.nanoTime()
        val now = clock()
        val rawCutoff = now - TimeUnit.HOURS.toMillis(config.rawRetentionHours.toLong())
        val rollupCutoff = minuteOf(now - TimeUnit.DAYS.toMillis(config.rollupRetentionDays.toLong()))
        try {
            val removed = store.pruneMetrics(rawCutoff, rollupCutoff, maxBytes)
            if (removed > 0) log.info("Request metrics for {}: retention removed {} row(s)", profile, removed)
        } catch (e: SQLException) {
            log.warn("Request metrics for {}: retention failed ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("Request metrics for {}: retention failed ({})", profile, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RequestMetricsRecorder::class.java)
        const val DEFAULT_WAIT_MS = 10_000L
        private const val QUERY_WAIT_MS = 2_000L
        private const val JOIN_TIMEOUT_MS = 10_000L
        private val WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1)
    }
}
