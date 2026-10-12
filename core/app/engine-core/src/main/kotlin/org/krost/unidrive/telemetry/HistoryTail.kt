package org.krost.unidrive.telemetry

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The live tail of one history: every row snapshot that reached the file is numbered, in commit order, and handed to the
 * listeners. Called by the writer thread after a batch committed; a listener must hand the row on and return.
 */
internal class HistoryTail(
    private val profile: String,
) {
    private val listeners = CopyOnWriteArrayList<(Long, TransferRow) -> Unit>()
    private val counter = AtomicLong()

    /** The number of the last snapshot committed (0 before any). A reader that read it first sees at least those rows. */
    val seq: Long
        get() = counter.get()

    fun add(listener: (seq: Long, row: TransferRow) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (seq: Long, row: TransferRow) -> Unit) {
        listeners -= listener
    }

    // A listener that throws loses that one row for itself, never the writer or the other listeners.
    @Suppress("TooGenericExceptionCaught")
    fun publish(rows: List<TransferRow>) {
        for (row in rows) {
            val seq = counter.incrementAndGet()
            for (listener in listeners) {
                try {
                    listener(seq, row)
                } catch (e: RuntimeException) {
                    log.warn("History tail listener for {} failed: {}", profile, e.javaClass.simpleName)
                }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(HistoryTail::class.java)
    }
}
