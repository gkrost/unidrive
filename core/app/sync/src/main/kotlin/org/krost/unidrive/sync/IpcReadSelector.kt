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

    @Volatile
    private var closed = false

    private val thread =
        Thread(::run, "ipc-select").apply {
            isDaemon = true
            start()
        }

    /**
     * Returns when [channel] has data, reached end of stream, or was closed ([wake] / [close]); the
     * caller reads to find out which. Cancellable. Throws [IOException] once this selector is closed.
     */
    suspend fun awaitReadable(channel: SocketChannel) {
        if (closed) throw IOException("the IPC read selector is closed")
        suspendCancellableCoroutine { cont ->
            waiting[channel] = cont
            cont.invokeOnCancellation { waiting.remove(channel, cont) }
            toArm.add(channel)
            selector.wakeup()
            // A close that slipped in between the checks above and the registration.
            if (closed || !channel.isOpen) wake(channel)
        }
    }

    /** Resumes the reader waiting on [channel], if any; called after the channel was closed by another path. */
    fun wake(channel: SocketChannel) {
        waiting.remove(channel)?.resume(Unit)
    }

    private fun run() {
        try {
            while (!closed) {
                while (true) arm(toArm.poll() ?: break)
                selector.select()
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

    override fun close() {
        closed = true
        runCatching { selector.wakeup() }
        thread.join(2_000)
    }
}
