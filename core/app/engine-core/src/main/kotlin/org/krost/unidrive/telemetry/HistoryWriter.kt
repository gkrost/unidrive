package org.krost.unidrive.telemetry

import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The one background thread behind a [TransferHistory]: a bounded queue of row snapshots in front, a
 * [TelemetryStore] behind. The producer side ([offer]) never blocks and never throws; everything that
 * touches the disk happens on the writer thread.
 */
internal class HistoryWriter(
    private val profile: String,
    private val config: HistoryConfig,
    private val clock: () -> Long,
    private val store: TelemetryStore,
) {
    private sealed interface Op {
        class Write(
            val row: TransferRow,
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
    private val thread = Thread(::writerLoop, "telemetry-writer-$profile").apply { isDaemon = true }

    // Writer thread only.
    private var lastPruneAt = 0L
    private var writtenSincePrune = 0L
    private var lastWarnNanos = 0L

    val stats: HistoryStats
        get() = HistoryStats(written.get(), dropped.get(), dropped.get() > 0, queue.size)

    fun start() = thread.start()

    /** Hot path: hands the snapshot to the queue; a full queue drops it and counts it. */
    fun offer(row: TransferRow) {
        if (!accepting.get()) return
        if (!queue.offer(Op.Write(row))) {
            dropped.incrementAndGet()
            droppedUnrecorded.set(true)
        }
    }

    fun flush(timeoutMs: Long): Boolean = control(timeoutMs) { Op.Barrier(it) }

    fun pruneNow() {
        control(DEFAULT_WAIT_MS) { Op.Prune(it) }
    }

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
            log.warn("History for {} did not answer within {} ms: {}", profile, timeoutMs, e.javaClass.simpleName)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** The process dies: nothing more is written. */
    fun abandon() {
        accepting.set(false)
        thread.interrupt()
        thread.join(JOIN_TIMEOUT_MS)
    }

    /** Writes what is queued and stops the thread. False when it was closed before. */
    fun close(): Boolean {
        if (!accepting.getAndSet(false)) return false
        if (thread.isAlive) {
            // A bounded wait: closing is not the hot path. A Stop that cannot be queued (a full queue
            // behind a long write) is delivered as an interrupt instead, so the thread always ends and
            // the store it writes through is never touched again.
            if (!queue.offer(Op.Stop, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) thread.interrupt()
            thread.join(JOIN_TIMEOUT_MS)
            if (thread.isAlive) thread.interrupt()
        }
        return true
    }

    private fun writerLoop() {
        val batch = ArrayList<Op>(config.batchSize)
        try {
            prune()
            var running = true
            while (running) {
                val first = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch.add(first)
                    queue.drainTo(batch, config.batchSize - 1)
                }
                running = process(batch)
                batch.clear()
                if (running) pruneIfDue()
            }
        } catch (e: InterruptedException) {
            log.debug("History writer for {} interrupted: {}", profile, e.message)
        }
    }

    /** Writes the batch's rows (the last snapshot of an operation wins) and runs the control ops in order. Returns false after Stop. */
    private fun process(batch: List<Op>): Boolean {
        val pending = LinkedHashMap<String, TransferRow>()
        var keepRunning = true
        for (op in batch) {
            when (op) {
                is Op.Write -> pending[op.row.operationId] = op.row
                is Op.Barrier -> {
                    writePending(pending)
                    op.done.complete(Unit)
                }
                is Op.Prune -> {
                    writePending(pending)
                    prune()
                    op.done.complete(Unit)
                }
                Op.Stop -> keepRunning = false
            }
        }
        writePending(pending)
        return keepRunning
    }

    @Suppress("TooGenericExceptionCaught") // history is never a correctness dependency: nothing here may kill the writer
    private fun writePending(pending: MutableMap<String, TransferRow>) {
        if (pending.isEmpty()) return
        val rows = pending.values.toList()
        pending.clear()
        try {
            store.upsert(rows)
            written.addAndGet(rows.size.toLong())
            writtenSincePrune += rows.size
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
                "History for {}: {} row(s) dropped, a transfer is not affected ({}: {}); {} dropped so far",
                profile,
                rows,
                e.javaClass.simpleName,
                HistorySanitizer.message(e.message),
                dropped.get(),
            )
        }
    }

    /** After a write succeeded: leave a mark in the file that rows are missing from it. */
    private fun recordGapIfAny() {
        if (!droppedUnrecorded.getAndSet(false)) return
        try {
            store.putMeta("incomplete_since", clock().toString(), onlyIfAbsent = true)
            store.putMeta("dropped_rows", dropped.get().toString())
        } catch (e: SQLException) {
            droppedUnrecorded.set(true)
            log.debug("History for {}: cannot record the gap yet: {}", profile, e.message)
        }
    }

    private fun pruneIfDue() {
        val due =
            writtenSincePrune >= config.pruneEveryRows ||
                System.nanoTime() - lastPruneAt >= TimeUnit.MILLISECONDS.toNanos(config.pruneIntervalMs)
        if (due) prune()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun prune() {
        lastPruneAt = System.nanoTime()
        writtenSincePrune = 0
        val cutoff = clock() - TimeUnit.DAYS.toMillis(config.maxAgeDays.toLong())
        try {
            val removed = store.prune(cutoff, config.maxBytes)
            if (removed > 0) log.info("History for {}: retention removed {} row(s)", profile, removed)
        } catch (e: SQLException) {
            log.warn("History for {}: retention failed ({})", profile, HistorySanitizer.message(e.message))
        } catch (e: RuntimeException) {
            log.warn("History for {}: retention failed ({})", profile, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HistoryWriter::class.java)
        const val DEFAULT_WAIT_MS = 10_000L
        private const val JOIN_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 500L
        private val WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1)
    }
}
