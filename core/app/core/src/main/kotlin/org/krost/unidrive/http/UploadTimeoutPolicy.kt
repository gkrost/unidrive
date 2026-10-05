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
 *
 * **The socket watchdog is read-idle (#517 F1).** `SOCKET_TIMEOUT_MS` fires
 * when no bytes have been RECEIVED for the interval — during a PUT to a peer
 * that stays silent until the response, that is the whole upload, body
 * flowing at line rate or not. Over TLS, Ktor reports the fired watchdog as
 * "the server prematurely closed the connection", indistinguishable from a
 * real close; every observed cut landed within ~1.5 s of the limit. The flat
 * 60 s watchdog therefore capped uploads at what 60 s carries (~362 MB on the
 * measured 48 Mbit/s line) and discarded every byte on the way. #517 R1 then
 * sized the watchdog for the body at an assumed 2 MiB/s floor, which still cut
 * every large upload on a slower link at the same point (#571: 1.36 GB at
 * 1.98 MB/s, cut at 650 s on every attempt). A read-idle timer cannot tell
 * "slow but moving" from "stuck" during a PUT at any size, so a PUT whose
 * body can be watched runs under [withUploadWatchdog] instead: cut only when
 * no body byte was written for [UPLOAD_IDLE_WINDOW_MS], or when the server
 * did not answer within [computeResponseWaitMs] after the body was sent. The
 * engine's own socket and request timeouts for that PUT become the outer net
 * of [computeWatchedOuterLimitMs].
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

    /**
     * #571: how long a watched upload may go without writing a single body
     * byte before [withUploadWatchdog] cuts it as stalled. Twice the 60 s
     * socket timeout that is the stall detector of every other call: long
     * enough for a Wi-Fi roam or a TCP retransmission backoff to recover, short
     * against the 11 minutes a single attempt of the live case took. Progress
     * is counted per 64 KiB chunk the engine's body channel accepted, and that
     * channel takes chunks again only once its 1 MiB buffer was handed on
     * (Ktor's CHANNEL_MAX_SIZE): progress moves in steps of up to 1 MiB, 102 s
     * at the 10 KiB/s floor of the Internxt outer net — still inside the window.
     */
    public const val UPLOAD_IDLE_WINDOW_MS: Long = 120_000L

    /**
     * #571: the least time a watched upload's server gets to answer after the
     * last body byte was written. In that time what still sits in the socket
     * buffers (a few MB at most, under a minute down to ~100 KB/s) reaches the
     * server and the server stores the object. Not measured for the shard
     * backend; five minutes is generous for both. Larger bodies get more
     * through [RESPONSE_WAIT_MIN_THROUGHPUT_BYTES_PER_SECOND].
     */
    public const val RESPONSE_WAIT_FLOOR_MS: Long = 300_000L

    /**
     * #571: the rate at which the server is assumed to at least store what it
     * received, for the size-dependent part of [computeResponseWaitMs]. 10 MiB/s
     * gives a 5 GiB body 512 s; below ~3 GB the floor wins.
     */
    public const val RESPONSE_WAIT_MIN_THROUGHPUT_BYTES_PER_SECOND: Long = 10L * 1024 * 1024

    /**
     * #571: how long [withUploadWatchdog] waits for the server's answer after
     * the last body byte of a [fileSize]-byte upload was written: the larger
     * of [floorMs] and the size at
     * [RESPONSE_WAIT_MIN_THROUGHPUT_BYTES_PER_SECOND].
     */
    public fun computeResponseWaitMs(
        fileSize: Long,
        floorMs: Long = RESPONSE_WAIT_FLOOR_MS,
    ): Long = computeRequestTimeoutMs(fileSize, floorMs, RESPONSE_WAIT_MIN_THROUGHPUT_BYTES_PER_SECOND)

    /**
     * #571: the engine's own socket AND request timeout for a watched upload
     * of [fileSize] bytes — the outer safety net behind [withUploadWatchdog].
     * The read-idle socket timer runs from the start of the request (the
     * server is silent while the body flows), so it must not fire before the
     * watchdog's limits can: the time the body needs at
     * [minThroughputBytesPerSecond] (with the [DEFAULT_FLOOR_MS] floor), plus
     * one idle window and the response wait. An upload slower than
     * [minThroughputBytesPerSecond] on average is the only one this net can
     * cut while it still moves; the caller picks a floor far below any usable
     * link (10 KiB/s for the Internxt shard backend).
     */
    public fun computeWatchedOuterLimitMs(
        fileSize: Long,
        minThroughputBytesPerSecond: Long,
        idleWindowMs: Long,
        responseWaitMs: Long,
    ): Long {
        val body = computeRequestTimeoutMs(fileSize, DEFAULT_FLOOR_MS, minThroughputBytesPerSecond)
        val sum = body + idleWindowMs + responseWaitMs
        // Saturate: an opted-out body bound (Long.MAX_VALUE) stays unbounded.
        return if (sum < body) Long.MAX_VALUE else sum
    }

    /**
     * True when an IOException surfaced after roughly the connection's own
     * socket-watchdog limit: then the cut was almost certainly OUR read-idle
     * watchdog, not a server event. Over TLS the two are indistinguishable in
     * the log (#517 F1, loopback probe C), but every observed watchdog cut
     * landed within ~1.5 s of the limit (59.84–61.48 s for a 60 s watchdog,
     * n=64), so elapsed ≈ limit identifies it. The 5 % slack absorbs the
     * teardown between timer fire and catch; a real close at 58 s is
     * misclassified, which only costs the difference in log wording. Watched
     * uploads (#571) use it against [computeWatchedOuterLimitMs].
     */
    public fun isSocketWatchdogCut(elapsedMs: Long, socketTimeoutMs: Long): Boolean =
        elapsedMs >= socketTimeoutMs * 95L / 100L
}
