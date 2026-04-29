package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import java.nio.file.Path

/**
 * CloudProvider decorator that enforces a maximum aggregate transfer rate.
 *
 * Rate is applied per-transfer: after each download or upload completes, the
 * decorator computes how many milliseconds the transfer *should* have taken at
 * [maxBytesPerSecond] and sleeps the deficit.  This is an after-the-fact token
 * bucket — simple, correct, and provider-agnostic.
 *
 * @param inner            The real provider to delegate all calls to.
 * @param maxBytesPerSecond Maximum combined byte rate (bytes/s). Must be > 0.
 */
class ThrottledProvider(
    private val inner: CloudProvider,
    private val maxBytesPerSecond: Long,
) : CloudProvider by inner {
    init {
        require(maxBytesPerSecond > 0) { "maxBytesPerSecond must be positive" }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val startMs = System.currentTimeMillis()
        val bytes = inner.download(remotePath, destination)
        throttle(bytes, startMs)
        return bytes
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val startMs = System.currentTimeMillis()
        val bytes = inner.downloadById(remoteId, remotePath, destination)
        throttle(bytes, startMs)
        return bytes
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val startMs = System.currentTimeMillis()
        val result = inner.upload(localPath, remotePath, onProgress)
        // Use the result size as bytes transferred; fall back to 0 if unavailable.
        throttle(result.size.coerceAtLeast(0), startMs)
        return result
    }

    /**
     * Sleep long enough that the transfer did not exceed [maxBytesPerSecond].
     *
     * minimumDurationMs = (bytes / maxBytesPerSecond) * 1000
     * deficitMs         = minimumDurationMs - actualDurationMs
     */
    private suspend fun throttle(
        bytes: Long,
        startMs: Long,
    ) {
        if (bytes <= 0) return
        val actualMs = System.currentTimeMillis() - startMs
        val minMs = bytes * 1000L / maxBytesPerSecond
        val sleepMs = minMs - actualMs
        if (sleepMs > 0) delay(sleepMs)
    }
}
