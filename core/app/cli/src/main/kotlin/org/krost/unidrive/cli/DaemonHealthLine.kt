package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

/**
 * The daemon's periodic health line (#891): one INFO line every [intervalMs] in the shared unidrive.log with the
 * heap (used/committed/max from `MemoryMXBean`), the GC count and time, the thread count, and — through the
 * suppliers the runtime wires — the enumeration state and items and the upload queue depth, all in `key=value`
 * form so a long scan's memory need is visible while it happens and a shared log splits per field without a
 * status call. No paths, no names. Cancelled with the serve scope at shutdown; nothing is logged at start.
 */
class DaemonHealthLine(
    private val intervalMs: Long,
    private val scope: CoroutineScope,
    /** The enumeration's state name and item count (items null when unknown), or null to leave both fields out. */
    private val enumeration: () -> Pair<String, Int?>? = { null },
    /** The upload queue depth, or null to leave the field out. */
    private val uploadQueueDepth: (suspend () -> Int?) = { null },
    /** The sink of the rendered line; production logs it at INFO. */
    private val logLine: (String) -> Unit = { log.info("{}", it) },
    /** The wait between lines; virtual time in tests. */
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) {
    fun start(): Job =
        scope.launch {
            while (isActive) {
                delayMs(intervalMs)
                logLine(render())
            }
        }

    internal suspend fun render(): String {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        var gcCount = 0L
        var gcTimeMs = 0L
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.collectionCount
            gcTimeMs += gc.collectionTime
        }
        return buildString {
            append("health")
            append(" heap_used_mib=").append(heap.used / MIB)
            append(" heap_committed_mib=").append(heap.committed / MIB)
            append(" heap_max_mib=").append(heap.max / MIB)
            append(" gc_count=").append(gcCount)
            append(" gc_time_ms=").append(gcTimeMs)
            append(" threads=").append(ManagementFactory.getThreadMXBean().threadCount)
            enumeration()?.let { (state, items) ->
                append(" enumeration=").append(state)
                items?.let { append(" items=").append(it) }
            }
            uploadQueueDepth()?.let { append(" upload_queue=").append(it) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DaemonHealthLine::class.java)
        private const val MIB = 1024L * 1024L
    }
}
