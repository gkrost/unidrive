package org.krost.unidrive.webdav

/**
 * UD-277: size-adaptive HTTP request-timeout for WebDAV upload/download.
 *
 * The pre-fix timeout was [Long.MAX_VALUE] (effectively unbounded — UD-285
 * removed the flat 600 s cap because a 277 MB file at 0.6 MB/s legitimately
 * exceeds it). That works for "slow but completing" but offers no defence
 * against "indefinitely slow" — a connection that delivers 1 byte per
 * 30 seconds would never trip the [HttpDefaults.SOCKET_TIMEOUT_MS] socket
 * watchdog (60 s no-bytes) yet still take days to complete a 1 GB file.
 *
 * Size-adaptive timeout splits the difference: bound a request to "the
 * time a half-decent link should need". For a 2 GiB file at 512 KiB/s
 * minimum throughput, that's exactly 4096 seconds (UD-277 acceptance #2).
 */
internal object WebDavTimeoutPolicy {
    /**
     * Compute the HTTP request-timeout for a transfer of [fileSize] bytes,
     * given a per-request [floorMs] and a [minThroughputBytesPerSecond] the
     * link must sustain.
     *
     * Returns [Long.MAX_VALUE] when [minThroughputBytesPerSecond] <= 0
     * (effective opt-out — keeps the UD-285 unbounded behaviour).
     */
    fun computeRequestTimeoutMs(
        fileSize: Long,
        floorMs: Long,
        minThroughputBytesPerSecond: Long,
    ): Long {
        if (minThroughputBytesPerSecond <= 0L) return Long.MAX_VALUE
        if (fileSize <= 0L) return floorMs

        // Ceiling-divide: if even one byte spills past a whole-second
        // boundary, allocate that next second too. Avoids the off-by-one
        // case where fileSize=524289 + 524288 B/s = 1.0019 s — without
        // the +1, computed as 1 s and the last byte would land just after
        // the deadline.
        val seconds =
            fileSize / minThroughputBytesPerSecond +
                if (fileSize % minThroughputBytesPerSecond > 0L) 1L else 0L

        // Saturating multiplication: clamp to Long.MAX_VALUE so a 4 EB file
        // at 1 KB/s doesn't overflow the *1000 below.
        if (seconds > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE
        return maxOf(floorMs, seconds * 1000L)
    }
}
