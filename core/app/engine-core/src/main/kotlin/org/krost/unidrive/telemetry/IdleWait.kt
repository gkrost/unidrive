package org.krost.unidrive.telemetry

import java.util.concurrent.TimeUnit

/**
 * How long a writer thread may block on an empty queue: until the nearest thing that falls due without a new
 * element ([dueInNs], in nanoseconds, negative when it is overdue). A row, a control op and the stop wake the
 * thread by arriving in the queue. A fixed poll of 500 ms woke each of the two writers twice a second all day,
 * with nothing to write, to find that nothing was due.
 */
internal fun idleWaitMs(dueInNs: Long): Long = maxOf(1L, (dueInNs + NANOS_PER_MS - 1) / NANOS_PER_MS)

private val NANOS_PER_MS = TimeUnit.MILLISECONDS.toNanos(1)
