package org.krost.unidrive.sync

/**
 * UD-106: simple token-bucket rate limiter for per-connection NDJSON dispatch.
 *
 * Not thread-safe — callers hold a per-connection instance and access it
 * serially from the connection's read loop.
 *
 * @property capacity max burst size; also the starting token count.
 * @property refillPerSecond tokens added per wall-clock second (may be 0).
 * @property clock injectable monotonic nano-timestamp source (for tests).
 */
class TokenBucket(
    private val capacity: Int,
    private val refillPerSecond: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, got $capacity" }
        require(refillPerSecond >= 0) { "refillPerSecond must be >= 0, got $refillPerSecond" }
    }

    private var tokens: Double = capacity.toDouble()
    private var lastTickNanos: Long = clock()

    /** @return true if a token was available and taken, false otherwise. */
    fun tryTake(): Boolean {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }

    private fun refill() {
        if (refillPerSecond == 0) return
        val now = clock()
        val elapsedNs = now - lastTickNanos
        if (elapsedNs <= 0) return
        lastTickNanos = now
        tokens = (tokens + elapsedNs * refillPerSecond / 1_000_000_000.0).coerceAtMost(capacity.toDouble())
    }
}
