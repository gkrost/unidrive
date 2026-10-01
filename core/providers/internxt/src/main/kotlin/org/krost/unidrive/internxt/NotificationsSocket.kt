package org.krost.unidrive.internxt

import io.socket.client.IO
import io.socket.client.Socket
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The slice of socket.io's [Socket] that [NotificationsClient] uses. A seam so
 * the client's reconnect / refresh / logging behaviour is testable without a
 * network (a fake socket that fails N times, a fake clock).
 */
internal interface NotificationsSocket {
    /** Register [listener] for [event]; it receives the event's arguments. */
    fun on(
        event: String,
        listener: (List<Any?>) -> Unit,
    )

    fun connect()

    /** Disconnect and drop every listener. Never throws. */
    fun close()
}

internal fun interface NotificationsSocketFactory {
    fun create(
        url: String,
        token: String,
    ): NotificationsSocket
}

/** Production factory: a socket.io client configured like Internxt's own desktop app. */
internal object SocketIoNotificationsSocketFactory : NotificationsSocketFactory {
    private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
    internal const val RECONNECT_MAX_DELAY_MS = 60_000L

    override fun create(
        url: String,
        token: String,
    ): NotificationsSocket {
        val options =
            IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .setReconnectionDelay(RECONNECT_INITIAL_DELAY_MS)
                .setReconnectionDelayMax(RECONNECT_MAX_DELAY_MS)
                .setReconnectionAttempts(Integer.MAX_VALUE)
                .build()
        return SocketIoNotificationsSocket(IO.socket(URI.create(url), options))
    }
}

private class SocketIoNotificationsSocket(
    private val socket: Socket,
) : NotificationsSocket {
    private val log = LoggerFactory.getLogger(SocketIoNotificationsSocket::class.java)

    override fun on(
        event: String,
        listener: (List<Any?>) -> Unit,
    ) {
        socket.on(event) { args -> listener(args.toList()) }
    }

    override fun connect() {
        socket.connect()
    }

    override fun close() {
        try {
            socket.disconnect()
            socket.off()
        } catch (e: Exception) {
            log.debug("closing the notifications socket raised (ignored)", e)
        }
    }
}

/**
 * Where [NotificationsClient] runs work that must not block socket.io's event
 * thread (the token supplier is a suspend function doing an HTTP round-trip)
 * and delayed retries. A seam so tests drive time by hand.
 */
internal interface NotificationsScheduler {
    /** Run [task] on another thread as soon as possible. */
    fun execute(task: Runnable)

    /** Run [task] on another thread after [delayMs]. */
    fun schedule(
        delayMs: Long,
        task: Runnable,
    )

    /** Cancel pending work and release the thread. Idempotent. */
    fun shutdown()
}

/**
 * One daemon worker thread, created on first use, so a client that never needs
 * a refresh costs no thread and a leaked one cannot keep the JVM alive.
 */
internal class ThreadNotificationsScheduler : NotificationsScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "unidrive-internxt-notifications-refresh").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    override fun execute(task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // shut down by disconnect(); nothing left to do
        }
    }

    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ) {
        try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // shut down by disconnect(); nothing left to do
        }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
