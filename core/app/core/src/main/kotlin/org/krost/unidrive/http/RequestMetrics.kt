package org.krost.unidrive.http

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * How one HTTP request attempt ended. A status class when a response arrived and its body was read to the end,
 * a transport class when it did not (a reset or timeout after the headers is still a transport class: the
 * attempt did not deliver what the status promised). [wire] is the stable text stored in `telemetry.db` and
 * returned by `metrics.query`.
 */
enum class AttemptOutcome(
    val wire: String,
    /** The attempt is one a caller may repeat: it was throttled, failed on the server, did not get through, or lost its credentials. */
    val retryable: Boolean,
) {
    OK("2xx", false),
    REDIRECT("3xx", false),
    UNAUTHORIZED("401_403", true),
    CLIENT_ERROR("4xx", false),
    THROTTLED("429", true),
    SERVER_ERROR("5xx", true),
    OTHER_STATUS("other", false),
    TIMEOUT("timeout", true),
    RESET("reset", true),
    TLS("tls", true),
    DNS("dns", true),
    CONNECT("connect", true),
    IO("io", true),
    CANCELLED("cancelled", false),
    ;

    companion object {
        fun ofWire(wire: String): AttemptOutcome? = entries.firstOrNull { it.wire == wire }

        fun ofStatus(status: Int): AttemptOutcome =
            when (status) {
                in 200..299 -> OK
                in 300..399 -> REDIRECT
                401, 403 -> UNAUTHORIZED
                429 -> THROTTLED
                in 400..499 -> CLIENT_ERROR
                in 500..599 -> SERVER_ERROR
                else -> OTHER_STATUS
            }

        /**
         * The transport class of a failure, read from the exception and its causes (a Ktor or engine exception wraps
         * the JDK's). Order matters: a TLS failure is also an I/O failure, a timeout can arrive as a cancellation.
         */
        fun ofFailure(failure: Throwable): AttemptOutcome {
            val chain = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).toList()
            return when {
                chain.any { it.isTimeout() } -> TIMEOUT
                chain.any { it is SSLException } -> TLS
                chain.any { it is UnknownHostException } -> DNS
                chain.any { it is ConnectException || it is NoRouteToHostException } -> CONNECT
                chain.any { it.isReset() } -> RESET
                chain.first() is CancellationException -> CANCELLED
                else -> IO
            }
        }

        private const val MAX_CAUSE_DEPTH = 8

        private fun Throwable.isTimeout(): Boolean =
            this is SocketTimeoutException || this is TimeoutException || javaClass.simpleName in TIMEOUT_NAMES

        private fun Throwable.isReset(): Boolean {
            if (this is EOFException || this is SocketException) return true
            if (this !is IOException) return false
            val text = message?.lowercase() ?: return false
            return RESET_HINTS.any { it in text }
        }

        // Ktor's own timeout exceptions live in modules this one does not name by type.
        private val TIMEOUT_NAMES =
            setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "SocketTimeoutException", "TimeoutCancellationException")
        private val RESET_HINTS = listOf("reset", "broken pipe", "prematurely", "closed")
    }
}

/**
 * One finished HTTP request attempt, as handed to a [RequestMetricsSink]. Nothing in it can identify content: the
 * endpoint is a class (see [EndpointClassifier]), never a URL.
 *
 * - [headersMs]: from handing the request to the engine until the response headers arrived; null when no response did.
 * - [totalMs]: until the response body was consumed, or the call failed or was cancelled; includes [headersMs].
 * - [bytesUp]: the request body's declared length; null when it is streamed without one or no response arrived.
 * - [bytesDown]: the response body's declared length (Content-Length); null when the body was streamed without one, or
 *   did not arrive whole. Ktor has no public byte counter, and teeing the body to count it would cost a copy.
 * - [retryNo]: how many failed attempts of the same request (same method and URL) came shortly before; 0 for a first attempt.
 * - [throttled]: the answer was a 429 or carried a Retry-After header.
 */
data class RequestAttempt(
    val startedAtMs: Long,
    val provider: String,
    val endpointClass: String,
    val method: String,
    val outcome: AttemptOutcome,
    val headersMs: Long?,
    val totalMs: Long,
    val bytesUp: Long?,
    val bytesDown: Long?,
    val retryNo: Int,
    val throttled: Boolean,
)

/** Where attempts go. [record] runs on the request's own thread: it must not block and must not throw. */
fun interface RequestMetricsSink {
    fun record(attempt: RequestAttempt)
}

/**
 * The late-bound seam between an HTTP client and whoever records its attempts: a client is built before the daemon
 * has opened its history, so the plugin reads [sink] per request. With no sink the plugin does nothing else.
 */
class RequestMetricsTap {
    @Volatile
    var sink: RequestMetricsSink? = null
}

internal fun nanosToMs(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos.coerceAtLeast(0))

/**
 * Infers [RequestAttempt.retryNo]: providers retry above the HTTP client, so the client sees only a new request.
 * One that repeats the method and URL of a request that failed in a retryable way within [windowNanos] is the next
 * attempt of it. Keys are hashes held in memory only; the table is bounded.
 */
internal class RetryTracker(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        var failures: Int,
        var lastNanos: Long,
    )

    private val entries =
        object : LinkedHashMap<Long, Entry>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > capacity
        }

    /** The retry number for a request that is about to be sent. */
    @Synchronized
    fun retryNo(
        key: Long,
        nowNanos: Long,
    ): Int {
        val entry = entries[key] ?: return 0
        if (nowNanos - entry.lastNanos > windowNanos) {
            entries.remove(key)
            return 0
        }
        return entry.failures
    }

    /** The attempt ended: a retryable failure counts toward the next attempt of the same request, anything else resets it. */
    @Synchronized
    fun ended(
        key: Long,
        retryable: Boolean,
        nowNanos: Long,
    ) {
        if (!retryable) {
            entries.remove(key)
            return
        }
        val entry = entries.getOrPut(key) { Entry(0, nowNanos) }
        entry.failures += 1
        entry.lastNanos = nowNanos
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
        val DEFAULT_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(10)
        private const val LOAD_FACTOR = 0.75f

        /** FNV-1a over the text: enough to tell requests apart in a table of a few hundred. */
        fun keyOf(text: String): Long {
            var h = FNV_OFFSET
            for (c in text) {
                h = h xor c.code.toLong()
                h *= FNV_PRIME
            }
            return h
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}
