package org.krost.unidrive.sync

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Wakes the per-connection reader of [IpcServer] when its socket has data (#687), so a request is
 * read at once and an idle connection costs nothing between its timeout checks.
 *
 * One daemon thread owns one [Selector] for all connections. A reader that found its non-blocking
 * channel empty calls [awaitReadable]; the thread resumes it when the channel becomes readable (the
 * selector is level-triggered, so data that arrived between the empty read and the registration is
 * not lost). The channels stay non-blocking: other coroutines write to them through the server's
 * non-blocking write with its own timeout, which a blocking read would take away.
 *
 * Interest is registered per wait and cleared when it fires, so a connection whose reader is busy
 * with a handler is not reported again until it waits again. A channel closed by someone else is
 * woken with [wake]: closing alone does not interrupt a selector.
 */
internal class IpcReadSelector : AutoCloseable {
    private val log = LoggerFactory.getLogger(IpcReadSelector::class.java)
    private val selector: Selector = Selector.open()

    // The reader waiting on each channel: the single source of truth, so a waiter is resumed once
    // by whoever removes it first (the selector thread, wake, or close).
    private val waiting = ConcurrentHashMap<SocketChannel, CancellableContinuation<Unit>>()
    private val toArm = ConcurrentLinkedQueue<SocketChannel>()

    // When a timed waiter is resumed without data (System.nanoTime). A waiter that was resumed or cancelled has no entry.
    private val deadlines = ConcurrentHashMap<SocketChannel, Long>()

    @Volatile
    private var closed = false

    private val thread =
        Thread(::run, "ipc-select").apply {
            isDaemon = true
            start()
        }

    /**
     * Returns when [channel] has data, reached end of stream, or was closed ([wake] / [close]), or, with
     * [timeoutMs], when that long has passed; the caller reads to find out which. The timeout is the selector
     * thread's own: it sleeps until the nearest deadline and resumes the waiter normally. A coroutine timeout
     * (`withTimeoutOrNull`) cancels the waiter with an exception instead, and builds a stack trace for it each
     * time: about 1.5 a second on a daemon with two idle connections, all day. Cancellable. Throws
     * [IOException] once this selector is closed.
     */
    suspend fun awaitReadable(
        channel: SocketChannel,
        timeoutMs: Long? = null,
    ) {
        if (closed) throw IOException("the IPC read selector is closed")
        suspendCancellableCoroutine { cont ->
            waiting[channel] = cont
            if (timeoutMs != null) deadlines[channel] = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            cont.invokeOnCancellation {
                if (waiting.remove(channel, cont)) deadlines.remove(channel)
            }
            toArm.add(channel)
            selector.wakeup()
            // A close that slipped in between the checks above and the registration.
            if (closed || !channel.isOpen) wake(channel)
        }
    }

    /** Resumes the reader waiting on [channel], if any; called after the channel was closed by another path. */
    fun wake(channel: SocketChannel) {
        deadlines.remove(channel)
        waiting.remove(channel)?.resume(Unit)
    }

    private fun run() {
        try {
            while (!closed) {
                while (true) arm(toArm.poll() ?: break)
                val sleepNs = deadlines.values.minOrNull()?.let { it - System.nanoTime() }
                if (sleepNs == null) selector.select() else selector.select(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(sleepNs + NANOS_PER_MS - 1)))
                val now = System.nanoTime()
                deadlines.entries.filter { now - it.value >= 0 }.forEach { wake(it.key) }
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    try {
                        key.interestOps(0)
                    } catch (_: CancelledKeyException) {
                        // the channel was closed; the waiter is woken below and finds out on its read
                    }
                    wake(key.channel() as SocketChannel)
                }
            }
        } catch (e: Exception) {
            if (!closed) log.error("IPC read selector failed; the connections close", e)
        } finally {
            closed = true
            runCatching { selector.close() }
            // Waiters resume and fail on their next read or wait, which ends their connections.
            waiting.keys.toList().forEach(::wake)
        }
    }

    private fun arm(channel: SocketChannel) {
        if (!channel.isOpen) return wake(channel)
        try {
            val key = channel.keyFor(selector)
            if (key == null) channel.register(selector, SelectionKey.OP_READ) else key.interestOps(SelectionKey.OP_READ)
        } catch (_: ClosedChannelException) {
            wake(channel)
        } catch (_: CancelledKeyException) {
            wake(channel)
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }

    override fun close() {
        closed = true
        runCatching { selector.wakeup() }
        thread.join(2_000)
    }
}
