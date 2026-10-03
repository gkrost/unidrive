package org.krost.unidrive.http

/**
 * UD-337 / UD-277: size-adaptive HTTP request-timeout policy for data-plane
 * uploads (and slow downloads where the body size is known up front).
 *
 * **The problem.** A flat `requestTimeoutMillis` (e.g. the
 * `HttpDefaults.REQUEST_TIMEOUT_MS = 600_000` 10-minute cap that every
 * Ktor-using provider installs by default) is wrong for data-plane PUTs:
 * a 5 GiB file at 1 MB/s legitimately takes ~85 minutes, but Ktor will
 * tear down the still-progressing connection at 10 minutes and the
 * upload fails — even though the **remote was still willing**. The
 * client timed out, not the server. User-reported as fatal in 2026-04-30
 * Internxt + ds418play sessions (UD-337).
 *
 * **The fix.** Bound the request to "the time a half-decent link should
 * need" — a function of the file size and a minimum-throughput floor.
 * For a 2 GiB file at 512 KiB/s that's exactly 4096 seconds; for a
 * 5 GiB file at 50 KiB/s (the conservative default) it's ~28 hours.
 * The 60 s `SOCKET_TIMEOUT_MS` watchdog still catches genuinely-stuck
 * connections (no bytes between reads), so this policy bounds without
 * overcommitting.
 *
 * **History.** Originally lived as `WebDavTimeoutPolicy` in the WebDAV
 * provider (UD-277). Lifted to `:app:core` under UD-337 so Internxt /
 * OneDrive / HiDrive / S3 stop using the flat 600 s cap on uploads.
 *
 * Use at every per-call data-plane PUT / POST site:
 * ```kotlin
 * httpClient.put(url) {
 *     timeout {
 *         requestTimeoutMillis = computeRequestTimeoutMs(
 *             fileSize = fileSize,
 *             floorMs = UploadTimeoutPolicy.DEFAULT_FLOOR_MS,
 *             minThroughputBytesPerSecond = UploadTimeoutPolicy.DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND,
 *         )
 *     }
 *     setBody(...)
 * }
 * ```
 */
public object UploadTimeoutPolicy {
    /**
     * Per-PUT request-timeout floor. Small files always get at least this
     * much, regardless of size. 10 minutes — matches the old
     * `HttpDefaults.REQUEST_TIMEOUT_MS` flat cap that this policy
     * replaces, so behaviour for sub-floor files is unchanged.
     */
    public const val DEFAULT_FLOOR_MS: Long = 600_000L

    /**
     * Default minimum sustained throughput (bytes/sec) the size-adaptive
     * policy assumes a half-decent link will hit. 50 KiB/s — bounds a
     * 277 MB file (UD-277's empirical large-file baseline) to ~90 minutes,
     * tight enough to catch a 1-byte/sec slow-loris write but generous
     * enough to ride out short throughput dips.
     */
    public const val DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND: Long = 50L * 1024

    /**
     * Compute the HTTP request-timeout (in milliseconds) for a transfer
     * of [fileSize] bytes, given a per-request [floorMs] and the
     * [minThroughputBytesPerSecond] the link is assumed to sustain.
     *
     * Returns [Long.MAX_VALUE] when [minThroughputBytesPerSecond] is
     * non-positive — effectively opt-out, matches UD-285's pre-UD-277
     * unbounded behaviour.
     *
     * Returns at least [floorMs] for sub-floor file sizes, including
     * `fileSize <= 0` (metadata calls or empty PUTs).
     */
    public fun computeRequestTimeoutMs(
        fileSize: Long,
        floorMs: Long = DEFAULT_FLOOR_MS,
        minThroughputBytesPerSecond: Long = DEFAULT_MIN_THROUGHPUT_BYTES_PER_SECOND,
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

        // Saturating multiplication: clamp to Long.MAX_VALUE so a 4 EB
        // file at 1 KB/s doesn't overflow the *1000 below.
        if (seconds > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE
        return maxOf(floorMs, seconds * 1000L)
    }
}
